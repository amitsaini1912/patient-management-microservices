package org.pm.billingservice.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.TestcontainersConfiguration;
import org.pm.billingservice.card.CardInfo;
import org.pm.billingservice.card.FakeCardVaultClient;
import org.pm.billingservice.idempotency.IdempotencyFilter;
import org.pm.billingservice.processor.FakePaymentProcessor;
import org.pm.billingservice.service.BillingAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-card limit over real HTTP with a real Redis (ADR 0015). Small limit for this class only: 3 attempts per
 * card, one more every 2 s. Every test uses its own card (own fingerprint = own bucket).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "grpc.server.port=0", "billing.rate-limit.card.capacity=3", "billing.rate-limit.card.refill-every=2s"})
@Import(TestcontainersConfiguration.class)
class CardRateLimitIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private BillingAccountService billingAccountService;
    @Autowired private FakeCardVaultClient vault;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private UUID billingAccountId;

    @BeforeEach
    void setUp() {
        billingAccountId = billingAccountService
                .getOrCreateAccount(UUID.randomUUID(), "Test Patient", "patient@test.com").getId();
    }

    @Test
    void fourthAttemptWithOneCardIs429WithRetryAfterAndNothingIsCharged() throws Exception {
        String card = newCard();
        for (int i = 0; i < 3; i++) {
            assertThat(pay(newKey(), card).statusCode()).isEqualTo(201);
        }

        HttpResponse<String> limited = pay(newKey(), card);

        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).hasValue("2");
        assertThat(limited.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).contains("problem+json"));
        assertThat(objectMapper.readTree(limited.body()).get("title").asText()).isEqualTo("Too many attempts");
        assertThat(paymentsOfAccount()).isEqualTo(3); // the 4th left no row and never reached the processor
    }

    @Test
    void declinedAttemptsCountToo() throws Exception {
        // card testing is mostly declines: they must use up the bucket like approvals
        // FakePaymentProcessor declines this token; give it a fresh fingerprint (= its own bucket) for this test
        String declined = FakePaymentProcessor.DECLINED_TOKEN;
        vault.register(new CardInfo(declined, "0002", "VISA", "fp-" + UUID.randomUUID(), 12, 2099));
        for (int i = 0; i < 3; i++) {
            HttpResponse<String> attempt = pay(newKey(), declined);
            assertThat(attempt.statusCode()).isEqualTo(201);
            assertThat(objectMapper.readTree(attempt.body()).get("status").asText()).isEqualTo("FAILED");
        }
        assertThat(pay(newKey(), declined).statusCode()).isEqualTo(429);
    }

    @Test
    void anotherTokenForTheSameCardSharesTheLimitAnotherCardDoesNot() throws Exception {
        // the vault gives the same card a NEW token each time it's tokenized; the fingerprint stays the same
        String fingerprint = "fp-" + UUID.randomUUID();
        List<String> tokensOfOneCard = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String token = "tok_" + UUID.randomUUID();
            vault.register(new CardInfo(token, "1111", "VISA", fingerprint, 12, 2099));
            tokensOfOneCard.add(token);
        }

        for (int i = 0; i < 3; i++) {
            assertThat(pay(newKey(), tokensOfOneCard.get(i)).statusCode()).isEqualTo(201);
        }
        assertThat(pay(newKey(), tokensOfOneCard.get(3)).statusCode()).isEqualTo(429);
        assertThat(pay(newKey(), newCard()).statusCode()).isEqualTo(201);
    }

    @Test
    void aRefusedKeyIsNotBurnedTheSameKeyWorksAfterRetryAfter() throws Exception {
        String card = newCard();
        for (int i = 0; i < 3; i++) {
            pay(newKey(), card);
        }
        String key = newKey();
        HttpResponse<String> limited = pay(key, card);
        assertThat(limited.statusCode()).isEqualTo(429);

        Thread.sleep(Duration.ofSeconds(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow())));

        HttpResponse<String> retried = pay(key, card);
        assertThat(retried.statusCode()).isEqualTo(201);                               // really ran...
        assertThat(retried.headers().firstValue(IdempotencyFilter.REPLAYED_HEADER)).isEmpty(); // ...not a replayed 429
        assertThat(pay(newKey(), card).statusCode()).isEqualTo(429);                   // only one token came back
    }

    @Test
    void replayingAFinishedPaymentCostsNoAttempt() throws Exception {
        String card = newCard();
        String key = newKey();
        assertThat(pay(key, card).statusCode()).isEqualTo(201);
        for (int i = 0; i < 5; i++) {
            assertThat(pay(key, card).headers().firstValue(IdempotencyFilter.REPLAYED_HEADER)).hasValue("true");
        }

        assertThat(pay(newKey(), card).statusCode()).isEqualTo(201); // attempts 2 and 3 still there
        assertThat(pay(newKey(), card).statusCode()).isEqualTo(201);
        assertThat(pay(newKey(), card).statusCode()).isEqualTo(429);
    }

    @Test
    void concurrentAttemptsNeverGetMoreThanTheBucket() throws Exception {
        String card = newCard();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> statuses = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            statuses.add(pool.submit(() -> {
                go.await();
                return pay(newKey(), card).statusCode();
            }));
        }
        go.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> status : statuses) {
            codes.add(status.get());
        }
        pool.shutdown();

        assertThat(codes).filteredOn(c -> c == 201).hasSize(3); // one Lua script at a time: exactly the bucket
        assertThat(codes).filteredOn(c -> c == 429).hasSize(7);
        assertThat(paymentsOfAccount()).isEqualTo(3);
    }

    // ---- helpers ----

    /** A token the fake vault doesn't know yet: it gets its own fingerprint (SHA-256 of the token) = own bucket. */
    private static String newCard() {
        return "tok_" + UUID.randomUUID();
    }

    private HttpResponse<String> pay(String key, String token) throws Exception {
        String body = """
                {"billingAccountId":"%s","amount":"100.00","currency":"INR","paymentMethodToken":"%s"}"""
                .formatted(billingAccountId, token);
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/payments"))
                        .header("Content-Type", "application/json").header(IdempotencyFilter.HEADER, key)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private int paymentsOfAccount() {
        return jdbc.queryForObject("SELECT count(*) FROM payment WHERE billing_account_id = ?", Integer.class,
                billingAccountId);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }
}
