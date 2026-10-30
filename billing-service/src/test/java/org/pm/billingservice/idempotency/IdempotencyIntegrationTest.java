package org.pm.billingservice.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.TestcontainersConfiguration;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.processor.FakePaymentProcessor;
import org.pm.billingservice.service.BillingAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Idempotency-Key contract over real HTTP (real Tomcat threads, real Postgres), because the
 * interesting bugs only show up when requests really run at the same time.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "grpc.server.port=0")
@Import(TestcontainersConfiguration.class)
class IdempotencyIntegrationTest {

    private static final int THREADS = 16;

    @LocalServerPort private int port;
    @Autowired private BillingAccountService billingAccountService;
    @Autowired private FakePaymentProcessor processor;
    @Autowired private IdempotencyStore store;
    @Autowired private IdempotencyCleanupJob cleanupJob;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private UUID billingAccountId;

    @BeforeEach
    void setUp() {
        billingAccountId = billingAccountService
                .getOrCreateAccount(UUID.randomUUID(), "Test Patient", "patient@test.com").getId();
    }

    @AfterEach
    void tearDown() {
        processor.setLatency(Duration.ZERO);
    }

    // ---- the header itself ----

    @Test
    void missingOrMalformedKeyIs400AndNothingHappens() throws Exception {
        assertThat(send("/payments", null, createBody("10.00")).statusCode()).isEqualTo(400);
        assertThat(send("/payments", "short", createBody("10.00")).statusCode()).isEqualTo(400);
        assertThat(send("/payments", "has spaces in it", createBody("10.00")).statusCode()).isEqualTo(400);

        assertThat(paymentsOfAccount()).isZero();
    }

    @Test
    void getNeedsNoKey() throws Exception {
        HttpResponse<String> created = send("/payments", newKey(), createBody("10.00"));
        HttpResponse<String> read = http.send(HttpRequest.newBuilder(uri("/payments/" + id(created))).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(read.statusCode()).isEqualTo(200);
    }

    // ---- sequential retries ----

    @Test
    void sameKeySameBodyReplaysTheFirstResponse() throws Exception {
        String key = newKey();

        HttpResponse<String> first = send("/payments", key, createBody("1000.00"));
        HttpResponse<String> second = send("/payments", key, createBody("1000.00"));

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(first.headers().firstValue(IdempotencyFilter.REPLAYED_HEADER)).isEmpty();
        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(second.headers().firstValue(IdempotencyFilter.REPLAYED_HEADER)).hasValue("true");
        assertThat(second.headers().firstValue("Location")).isEqualTo(first.headers().firstValue("Location"));
        assertThat(second.body()).isEqualTo(first.body()); // byte for byte, incl. timestamps
        assertThat(paymentsOfAccount()).isEqualTo(1);
        assertThat(processor.executions("authorize:" + id(first))).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentBodyIs422() throws Exception {
        String key = newKey();
        send("/payments", key, createBody("1000.00"));

        HttpResponse<String> reused = send("/payments", key, createBody("2000.00"));

        assertThat(reused.statusCode()).isEqualTo(422);
        assertThat(json(reused).get("title").asText()).isEqualTo("Idempotency-Key reused");
        assertThat(paymentsOfAccount()).isEqualTo(1);
    }

    @Test
    void sameKeyOnADifferentEndpointIs422() throws Exception {
        String key = newKey();
        String paymentId = id(send("/payments", key, createBody("1000.00")));

        // the path is part of the hash: a client bug that reuses one key for authorize and capture is caught
        assertThat(send("/payments/" + paymentId + "/capture", key, "").statusCode()).isEqualTo(422);
    }

    @Test
    void deterministicClientErrorsAreStoredAndReplayed() throws Exception {
        String key = newKey();
        String path = "/payments/" + UUID.randomUUID() + "/capture";

        HttpResponse<String> first = send(path, key, "");
        HttpResponse<String> second = send(path, key, "");

        assertThat(first.statusCode()).isEqualTo(404);
        assertThat(second.statusCode()).isEqualTo(404);
        assertThat(second.headers().firstValue(IdempotencyFilter.REPLAYED_HEADER)).hasValue("true");
    }

    @Test
    void serverErrorIsNotStoredSoTheSameKeyCanRetry() throws Exception {
        HttpResponse<String> created = send("/payments", newKey(), createBody("1000.00"));
        String paymentId = id(created);
        // the acquirer drops the hold behind our back -> our capture gets a 502
        processor.voidAuthorization("test-" + UUID.randomUUID(), processorReferenceOf(paymentId));
        String key = newKey();

        HttpResponse<String> failed = send("/payments/" + paymentId + "/capture", key, "");

        assertThat(failed.statusCode()).isEqualTo(502);
        assertThat(store.find(IdempotencyFilter.DEFAULT_SCOPE, key)).isEmpty(); // released, not stored
        // and the retry really runs again (still 502 here, but not a replay)
        HttpResponse<String> retried = send("/payments/" + paymentId + "/capture", key, "");
        assertThat(retried.statusCode()).isEqualTo(502);
        assertThat(retried.headers().firstValue(IdempotencyFilter.REPLAYED_HEADER)).isEmpty();
    }

    // ---- in progress / abandoned ----

    @Test
    void sameKeyWhileTheFirstIsStillRunningIs409() throws Exception {
        String key = newKey();
        String body = createBody("1000.00");
        Instant now = Instant.now();
        // a first request that has claimed the key and is still working on it
        store.tryClaim(IdempotencyFilter.DEFAULT_SCOPE, key, hash("/payments", body), now, now.plus(Duration.ofHours(1)));

        HttpResponse<String> second = send("/payments", key, body);

        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(second.headers().firstValue("Retry-After")).hasValue("1");
        assertThat(paymentsOfAccount()).isZero();
    }

    @Test
    void anAbandonedKeyIsTakenOverAfterTheLockTimeout() throws Exception {
        String key = newKey();
        String body = createBody("1000.00");
        Instant longAgo = Instant.now().minus(Duration.ofMinutes(5)).truncatedTo(ChronoUnit.MICROS);
        // its owner crashed 5 minutes ago (lock timeout is 60s)
        store.tryClaim(IdempotencyFilter.DEFAULT_SCOPE, key, hash("/payments", body), longAgo, longAgo.plus(Duration.ofHours(1)));

        HttpResponse<String> retry = send("/payments", key, body);

        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(store.find(IdempotencyFilter.DEFAULT_SCOPE, key).orElseThrow().status())
                .isEqualTo(IdempotencyRecord.Status.COMPLETED);
        // the crashed owner wakes up: it no longer owns the key and can't overwrite or delete it
        assertThat(store.complete(IdempotencyFilter.DEFAULT_SCOPE, key, longAgo, 500, null, null, new byte[0])).isFalse();
        store.release(IdempotencyFilter.DEFAULT_SCOPE, key, longAgo);
        assertThat(store.find(IdempotencyFilter.DEFAULT_SCOPE, key).orElseThrow().responseStatus()).isEqualTo(201);
    }

    // ---- expiry ----

    @Test
    void cleanupDeletesExpiredKeysOnly() {
        Instant now = Instant.now();
        String expired = newKey();
        String live = newKey();
        store.tryClaim(IdempotencyFilter.DEFAULT_SCOPE, expired, "0".repeat(64), now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(1)));
        store.tryClaim(IdempotencyFilter.DEFAULT_SCOPE, live, "0".repeat(64), now, now.plus(Duration.ofDays(1)));

        assertThat(cleanupJob.deleteExpired()).isGreaterThanOrEqualTo(1);

        assertThat(store.find(IdempotencyFilter.DEFAULT_SCOPE, expired)).isEmpty();
        assertThat(store.find(IdempotencyFilter.DEFAULT_SCOPE, live)).isPresent();
    }

    @Test
    void anExpiredKeyNotYetCleanedUpCountsAsNew() throws Exception {
        String key = newKey();
        Instant past = Instant.now().minus(Duration.ofDays(2)).truncatedTo(ChronoUnit.MICROS);
        store.tryClaim(IdempotencyFilter.DEFAULT_SCOPE, key, hash("/payments", createBody("1.00")), past, past.plus(Duration.ofDays(1)));
        store.complete(IdempotencyFilter.DEFAULT_SCOPE, key, past, 201, "application/json", null, "{}".getBytes());

        // a different body with that key would be a 422 if the row still counted
        HttpResponse<String> response = send("/payments", key, createBody("1000.00"));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue(IdempotencyFilter.REPLAYED_HEADER)).isEmpty();
    }

    // ---- real concurrent duplicates ----

    @Test
    void concurrentDuplicatesOfOneAuthorizeCreateExactlyOnePayment() throws Exception {
        String key = newKey();
        String body = createBody("1000.00");

        processor.setLatency(Duration.ofMillis(300)); // the duplicates arrive while the first is at the "acquirer"

        List<HttpResponse<String>> responses = concurrently(() -> send("/payments", key, body));

        // never a 500 (e.g. a primary key violation from two inserts): only the result or "in progress".
        // At least one 409 proves the requests really overlapped; all-201 would mean they ran one after another.
        assertThat(responses).extracting(HttpResponse::statusCode).containsOnly(201, 409);
        Set<String> ids = responses.stream().filter(r -> r.statusCode() == 201).map(this::id).collect(Collectors.toSet());
        assertThat(ids).hasSize(1);
        assertThat(paymentsOfAccount()).isEqualTo(1);
        assertThat(processor.executions("authorize:" + ids.iterator().next())).isEqualTo(1);
    }

    @Test
    void concurrentDuplicatesOfOneRefundRefundExactlyOnce() throws Exception {
        String paymentId = capturedPayment("1000.00");
        String key = newKey();
        String body = "{\"amount\":\"100.00\",\"currency\":\"INR\"}";

        processor.setLatency(Duration.ofMillis(300));

        List<HttpResponse<String>> responses = concurrently(() -> send("/payments/" + paymentId + "/refunds", key, body));

        assertThat(responses).extracting(HttpResponse::statusCode).containsOnly(201, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refund WHERE payment_id = ?", Integer.class,
                UUID.fromString(paymentId))).isEqualTo(1);
        assertThat(processor.hold(processorReferenceOf(paymentId)).refunded()).isEqualTo(Money.ofMinor(100_00, "INR"));
    }

    @Test
    void concurrentIdenticalCapturesWithDifferentKeysAllSucceedButCaptureOnce() throws Exception {
        // Different keys = the client really sent several "capture everything" requests at once. They share one
        // processor key (payment + amount), so the acquirer captures once; each request then finds the capture
        // already recorded and answers 200 with it (ADR 0010). Day 5 answered the losers 409 - also correct, but
        // step 3 can't tell a racing request from the acquirer's webhook, and both mean "this capture happened".
        String paymentId = id(send("/payments", newKey(), createBody("1000.00")));
        processor.setLatency(Duration.ofMillis(300)); // all of them pass the state check before anyone records

        List<HttpResponse<String>> responses = concurrently(() -> send("/payments/" + paymentId + "/capture", newKey(), ""));

        assertThat(responses).extracting(HttpResponse::statusCode).containsOnly(200);
        assertThat(processor.executions("capture:" + paymentId + ":100000:INR")).isEqualTo(1);
        assertThat(processor.hold(processorReferenceOf(paymentId)).captured()).isEqualTo(Money.ofMinor(1_000_00, "INR"));
        // one capture recorded = one ledger posting + one event
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'payment.captured'",
                Integer.class, UUID.fromString(paymentId))).isEqualTo(1);
    }

    // ---- helpers ----

    private interface Call {
        HttpResponse<String> send() throws Exception;
    }

    /** All threads wait on one latch, then fire together. */
    private List<HttpResponse<String>> concurrently(Call call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.send();
                }));
            }
            start.countDown();
            List<HttpResponse<String>> responses = new ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private HttpResponse<String> send(String path, String key, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            request.header(IdempotencyFilter.HEADER, key);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String capturedPayment(String amount) throws Exception {
        String paymentId = id(send("/payments", newKey(), createBody(amount)));
        assertThat(send("/payments/" + paymentId + "/capture", newKey(), "").statusCode()).isEqualTo(200);
        return paymentId;
    }

    private String processorReferenceOf(String paymentId) {
        return jdbc.queryForObject("SELECT processor_reference FROM payment WHERE id = ?", String.class,
                UUID.fromString(paymentId));
    }

    private int paymentsOfAccount() {
        return jdbc.queryForObject("SELECT count(*) FROM payment WHERE billing_account_id = ?", Integer.class,
                billingAccountId);
    }

    private String createBody(String amount) {
        return """
                {"billingAccountId":"%s","amount":"%s","currency":"INR","paymentMethodToken":"pm_card_visa"}"""
                .formatted(billingAccountId, amount);
    }

    private static String hash(String path, String body) {
        return IdempotencyFilter.requestHash("POST", path, body.getBytes(StandardCharsets.UTF_8));
    }

    private String id(HttpResponse<String> response) {
        return json(response).get("id").asText();
    }

    private JsonNode json(HttpResponse<String> response) {
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + response.body(), e);
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }
}
