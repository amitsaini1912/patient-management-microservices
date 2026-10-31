package org.pm.billingservice.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.processor.FakePaymentProcessor;
import org.pm.billingservice.service.BillingAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 23 (chaos): the DATABASE fails under billing - a Toxiproxy between billing and Postgres cuts connections or
 * takes the database away while payments run. (Kafka down, risk engine down/slow, duplicate webhooks and Redis down
 * have their own tests since Days 6/19/7/12.)
 *
 * What must hold whatever the database does: no money moves twice (acquirer executions), a client that retries with
 * the same Idempotency-Key ends with exactly one correct payment, the client is told to RETRY (503 + Retry-After) rather
 * than "something broke" (500), and billing recovers by itself when the database is back - no restart.
 *
 * Own Spring context (its own Postgres behind the proxy): the shared TestcontainersConfiguration's Postgres is
 * connected directly. Hikari's connection-timeout is 2 s here so an outage fails within the test's patience.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "grpc.server.port=0",
        "spring.datasource.hikari.connection-timeout=2000",
        "billing.idempotency.lock-timeout=3s"})
@Import(DatabaseChaosIntegrationTest.Containers.class)
class DatabaseChaosIntegrationTest {

    private static final Network NETWORK = Network.newNetwork();
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withNetwork(NETWORK).withNetworkAliases("postgres");
    private static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.9.0")
            .withNetwork(NETWORK);
    private static final Proxy DB;

    static {
        POSTGRES.start();
        TOXIPROXY.start();
        try {
            DB = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort())
                    .createProxy("postgres", "0.0.0.0:8666", "postgres:5432");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void datasourceThroughTheProxy(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://%s:%d/%s".formatted(
                TOXIPROXY.getHost(), TOXIPROXY.getMappedPort(8666), POSTGRES.getDatabaseName()));
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** Kafka + Redis like TestcontainersConfiguration, but no Postgres @ServiceConnection (it would skip the proxy). */
    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        KafkaContainer kafkaContainer() {
            return new KafkaContainer("apache/kafka:3.9.1");
        }

        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> redisContainer() {
            return new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
        }
    }

    @LocalServerPort private int port;
    @Autowired private BillingAccountService billingAccountService;
    @Autowired private FakePaymentProcessor processor;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private UUID accountId;
    private int retryable503s;

    @BeforeEach
    void setUp() throws IOException {
        DB.enable();
        // the previous test may have left dead pooled connections: each fails once, then Hikari replaces it
        for (int attempt = 1; ; attempt++) {
            try {
                accountId = billingAccountService.getOrCreateAccount(UUID.randomUUID(), "Chaos Patient", "chaos@test.com").getId();
                return;
            } catch (org.springframework.dao.DataAccessException e) {
                if (attempt == 15) throw e;
            }
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        processor.setLatency(Duration.ZERO);
        DB.enable();
    }

    /**
     * Postgres restarts (or fails over): every pooled connection is dead. Hikari skips its liveness check for a
     * connection used in the last 500 ms, so under traffic each dead connection fails ONE request before it is
     * dropped. Those requests must be 503 (retry, same key) - before Day 23 they were 500 - and the retries must end
     * in exactly one payment each, charged once.
     */
    @Test
    void aDatabaseRestartCostsAtMostOneRetryableAnswerPerPooledConnection() throws Exception {
        assertThat(authorize(newKey(), "100.00").statusCode()).isEqualTo(201);   // pool now holds live connections

        DB.disable();   // every pooled connection is cut
        DB.enable();

        retryable503s = 0;
        for (int i = 0; i < 15; i++) {
            String key = newKey();
            HttpResponse<String> response = retryWhileTemporary(key, "100.00");
            assertThat(response.statusCode()).as("payment %d after the restart", i).isEqualTo(201);
            assertThat(processor.executions("authorize:" + paymentId(key))).isEqualTo(1);
        }
        assertThat(retryable503s).as("503s").isLessThanOrEqualTo(10);   // Hikari's pool size; never a 500
    }

    @Test
    void databaseDownMeansA503FastAndTheSameKeyWorksWhenItIsBack() throws Exception {
        String key = newKey();
        DB.disable();

        long start = System.nanoTime();
        HttpResponse<String> during = authorize(key, "250.00");
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(during.statusCode()).isEqualTo(503);                              // retry later, not "broken"
        assertThat(during.headers().firstValue("Retry-After")).isPresent();
        assertThat(took).isLessThan(Duration.ofSeconds(5));                            // a busy thread, not a hung one
        assertThat(processor.executions("authorize:" + paymentId(key))).isZero();     // nothing charged

        DB.enable();
        HttpResponse<String> after = retryWhileTemporary(key, "250.00");
        assertThat(after.statusCode()).isEqualTo(201);
        assertThat(json(after).get("status").asText()).isEqualTo("AUTHORIZED");
        assertThat(processor.executions("authorize:" + paymentId(key))).isEqualTo(1);
    }

    @Test
    void connectionLostAfterTheAcquirerChargedIsHealedByTheRetryWithoutChargingTwice() throws Exception {
        String key = newKey();
        processor.setLatency(Duration.ofMillis(1500));              // the acquirer call is in flight...
        CompletableFuture<HttpResponse<String>> first = CompletableFuture.supplyAsync(() -> authorize(key, "999.00"));
        await(() -> processor.executions("authorize:" + paymentId(key)) == 1 || first.isDone());
        DB.disable();                                                // ...and the database goes away meanwhile
        HttpResponse<String> failed = first.get();
        processor.setLatency(Duration.ZERO);

        assertThat(failed.statusCode()).isEqualTo(503);                                // money moved, not recorded
        assertThat(processor.executions("authorize:" + paymentId(key))).isEqualTo(1);

        DB.enable();
        HttpResponse<String> retried = retryWhileTemporary(key, "999.00");
        assertThat(retried.statusCode()).isEqualTo(201);
        assertThat(json(retried).get("status").asText()).isEqualTo("AUTHORIZED");
        assertThat(processor.executions("authorize:" + paymentId(key))).isEqualTo(1);  // the same hold, reused
        UUID id = paymentId(key);
        assertThat(jdbc.queryForObject("select count(*) from payment where id = ?", Integer.class, id)).isEqualTo(1);
        // one event in the outbox; the ledger (Day 21 invariants) still balances entry by entry and in total
        assertThat(jdbc.queryForObject("""
                select count(*) from outbox_event where aggregate_id = ? and event_type = 'payment.authorized'""",
                Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                select journal_entry_id from entry_line group by journal_entry_id, currency
                having sum(case direction when 'DEBIT' then amount_minor else -amount_minor end) <> 0""", UUID.class))
                .isEmpty();
        assertThat(jdbc.queryForList("""
                select sum(case direction when 'DEBIT' then amount_minor else -amount_minor end)
                from entry_line group by currency""", Long.class)).allSatisfy(total -> assertThat(total).isZero());
    }

    // ---- helpers ----

    private HttpResponse<String> authorize(String key, String amount) {
        String body = """
                {"billingAccountId":"%s","amount":"%s","currency":"INR","paymentMethodToken":"pm_card_visa"}"""
                .formatted(accountId, amount);
        try {
            return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/payments"))
                            .header("Content-Type", "application/json").header("Idempotency-Key", key)
                            .timeout(Duration.ofSeconds(30))
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * What a client does with Retry-After: retry the same key while the answer is temporary. 409 = the key is still
     * "in progress" because the outage also stopped billing from releasing it (frees itself after the lock timeout:
     * 3 s here, 60 s live); 503 = the database (or a dead pooled connection) failed this attempt.
     */
    private HttpResponse<String> retryWhileTemporary(String key, String amount) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        HttpResponse<String> response = authorize(key, amount);
        while ((response.statusCode() == 409 || response.statusCode() == 503) && System.nanoTime() < deadline) {
            if (response.statusCode() == 503) retryable503s++;
            Thread.sleep(250);
            response = authorize(key, amount);
        }
        return response;
    }

    private JsonNode json(HttpResponse<String> response) throws IOException {
        return objectMapper.readTree(response.body());
    }

    private static UUID paymentId(String key) {
        return PaymentService.paymentIdFor(key);   // no X-User-Id header -> default scope -> the key as sent
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met in 10 s");
            Thread.sleep(20);
        }
    }
}
