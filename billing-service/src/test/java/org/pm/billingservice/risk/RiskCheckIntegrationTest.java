package org.pm.billingservice.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.TestcontainersConfiguration;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.payment.IllegalPaymentStateException;
import org.pm.billingservice.payment.Payment;
import org.pm.billingservice.payment.PaymentService;
import org.pm.billingservice.payment.PaymentStatus;
import org.pm.billingservice.processor.FakePaymentProcessor;
import org.pm.billingservice.processor.FakePaymentProcessor.HoldState;
import org.pm.billingservice.service.BillingAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The risk check inside a real authorize (real Postgres, fake acquirer + vault), through the real HTTP client to a
 * stub risk engine. Its own Spring context (risk enabled); the breaker is set never to open here so one test's
 * failures can't change another's - RiskAssessorTest covers the breaker.
 */
@SpringBootTest(properties = {"grpc.server.port=0", "billing.risk.enabled=true", "billing.risk.api-key=billing-it-key-0001",
        "billing.risk.circuit-breaker.minimum-calls=100000"})
@Import(TestcontainersConfiguration.class)
class RiskCheckIntegrationTest {

    private static final String CARD = "pm_card_visa";
    private static final HttpServer ENGINE;
    private static final List<JsonNode> REQUESTS = new CopyOnWriteArrayList<>();
    private static volatile int status;
    private static volatile String action;
    private static volatile long delayMs;

    static {
        try {
            ENGINE = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ObjectMapper json = new ObjectMapper();
        ENGINE.createContext("/v2/assess", exchange -> {
            REQUESTS.add(json.readTree(exchange.getRequestBody().readAllBytes()));
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = ("{\"action\":\"" + action + "\",\"reason_codes\":[\"R01_VELOCITY\",\"M_AMOUNT\"],"
                    + "\"model_score\":0.61,\"model_version\":\"rf-it\",\"rules_version\":\"rules-it\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        ENGINE.setExecutor(Executors.newCachedThreadPool()); // a slow answer must not hold up the next test's calls
        ENGINE.start();
    }

    @DynamicPropertySource
    static void engineUrl(DynamicPropertyRegistry registry) {
        registry.add("billing.risk.base-url", () -> "http://localhost:" + ENGINE.getAddress().getPort());
    }

    @AfterAll
    static void stopEngine() {
        ENGINE.stop(0);
    }

    @Autowired private PaymentService paymentService;
    @Autowired private BillingAccountService billingAccountService;
    @Autowired private FakePaymentProcessor processor;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MeterRegistry meters;

    private UUID accountId;

    @BeforeEach
    void reset() {
        REQUESTS.clear();
        status = 200;
        action = "allow";
        delayMs = 0;
        accountId = billingAccountService.getOrCreateAccount(UUID.randomUUID(), "Risk Patient", "risk@test.com").getId();
    }

    private Payment pay(String key, long paise) {
        return paymentService.authorize(key, accountId, Money.ofMinor(paise, "INR"), CARD, new RiskContext("203.0.113.9", "dev-7"));
    }

    private int processorCalls(Payment payment) {
        return processor.executions("authorize:" + payment.getId());
    }

    private List<String> events(Payment payment) {
        return jdbc.queryForList("SELECT event_type FROM outbox_event WHERE aggregate_id = ? ORDER BY created_at",
                String.class, payment.getId());
    }

    @Test
    void allowGoesToTheNetworkAndKeepsTheVerdict() {
        Payment payment = pay(UUID.randomUUID().toString(), 1_500_00);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(payment.getRiskDecision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(payment.getRiskSource()).isEqualTo(RiskSource.ENGINE);
        assertThat(payment.getRiskScore()).isEqualTo(0.61);
        assertThat(payment.getRiskReasonCodes()).isEqualTo("R01_VELOCITY,M_AMOUNT");
        assertThat(payment.getRiskModelVersion()).isEqualTo("rf-it");
        assertThat(processorCalls(payment)).isEqualTo(1);
        JsonNode facts = REQUESTS.get(0);
        assertThat(facts.get("transaction_id").asText()).isEqualTo(payment.getId().toString());
        assertThat(facts.get("customer_id").asText()).isEqualTo(accountId.toString());
        assertThat(facts.get("ip").asText()).isEqualTo("203.0.113.9");
        assertThat(facts.get("device_id").asText()).isEqualTo("dev-7");
        assertThat(facts.get("card_fingerprint").asText()).hasSize(64);           // the vault's fingerprint
    }

    @Test
    void holdNeverReachesTheNetworkUntilReviewed() {
        action = "hold_for_review";
        Payment payment = pay(UUID.randomUUID().toString(), 1_500_00);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING_REVIEW);
        assertThat(processorCalls(payment)).isZero();
        assertThat(events(payment)).containsExactly("payment.held_for_review");
        String audited = jdbc.queryForObject("SELECT details::text FROM audit_log WHERE entity_id = ? ORDER BY seq DESC LIMIT 1",
                String.class, payment.getId().toString());
        assertThat(audited).contains("\"risk_decision\":\"HOLD\"", "\"risk_source\":\"ENGINE\"");
    }

    @Test
    void blockFailsWithoutTheNetwork() {
        action = "block";
        Payment payment = pay(UUID.randomUUID().toString(), 1_500_00);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("risk_declined");
        assertThat(processorCalls(payment)).isZero();
        assertThat(events(payment)).containsExactly("payment.failed");
    }

    @Test
    void aRetryOfAHeldPaymentReturnsItWithoutAskingAgain() {
        action = "hold_for_review";
        String key = UUID.randomUUID().toString();
        Payment first = pay(key, 1_500_00);
        action = "allow";                                                         // even if the engine changed its mind

        Payment again = pay(key, 1_500_00);

        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(again.getStatus()).isEqualTo(PaymentStatus.PENDING_REVIEW);
        assertThat(REQUESTS).hasSize(1);
        assertThat(processorCalls(first)).isZero();
    }

    @Test
    void engineDownMeansFallbackRulesAfterOneRetry() {
        status = 503;
        Payment small = pay(UUID.randomUUID().toString(), 1_500_00);
        Payment big = pay(UUID.randomUUID().toString(), 75_000_00);

        assertThat(small.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);         // payments don't stop
        assertThat(small.getRiskSource()).isEqualTo(RiskSource.FALLBACK);
        assertThat(small.getRiskReasonCodes()).isEqualTo("FB_DEFAULT");
        assertThat(big.getStatus()).isEqualTo(PaymentStatus.PENDING_REVIEW);       // big ones wait for a person
        assertThat(big.getRiskReasonCodes()).isEqualTo("FB_HIGH_AMOUNT");
        assertThat(REQUESTS).hasSize(4);                                          // 2 payments x (call + 1 retry)
    }

    @Test
    void aSlowEngineCostsAtMostTheBudget() {
        delayMs = 2_000;
        long started = System.nanoTime();
        Payment payment = pay(UUID.randomUUID().toString(), 1_500_00);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(payment.getRiskSource()).isEqualTo(RiskSource.FALLBACK);
        assertThat(REQUESTS).hasSize(1);                                          // a timeout is not retried
        assertThat(took).isLessThan(Duration.ofMillis(1_900));                    // never waited for the 2 s answer
        // the risk step itself: the 300 ms budget plus scheduling noise (total time above includes cold DB/Redis paths)
        assertThat(meters.timer("billing.risk.duration", "source", "FALLBACK").max(TimeUnit.MILLISECONDS)).isLessThan(400);
    }

    @Test
    void approveAuthorizesAHeldPayment() {
        action = "hold_for_review";
        Payment held = pay(UUID.randomUUID().toString(), 1_500_00);

        Payment approved = paymentService.review(held.getId(), Payment.ReviewDecision.APPROVED, "admin@test.com", "known patient");

        assertThat(approved.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(approved.getReviewDecision()).isEqualTo(Payment.ReviewDecision.APPROVED);
        assertThat(approved.getReviewedBy()).isEqualTo("admin@test.com");
        assertThat(approved.getReviewedAt()).isNotNull();
        assertThat(processor.hold(approved.getProcessorReference()).state()).isEqualTo(HoldState.AUTHORIZED);
        assertThat(events(held)).containsExactly("payment.held_for_review", "payment.authorized");
        assertThatThrownBy(() -> paymentService.review(held.getId(), Payment.ReviewDecision.APPROVED, "admin@test.com", null))
                .isInstanceOf(IllegalPaymentStateException.class);               // not held any more: 409
        assertThat(processorCalls(held)).isEqualTo(1);
    }

    @Test
    void approvedButDeclinedByTheNetworkIsFailed() {
        action = "hold_for_review";
        Payment held = paymentService.authorize(UUID.randomUUID().toString(), accountId, Money.ofMinor(1_500_00, "INR"),
                FakePaymentProcessor.DECLINED_TOKEN, RiskContext.none());

        Payment reviewed = paymentService.review(held.getId(), Payment.ReviewDecision.APPROVED, "admin@test.com", null);

        assertThat(reviewed.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(reviewed.getFailureReason()).isEqualTo("card_declined");
        assertThat(reviewed.getReviewDecision()).isEqualTo(Payment.ReviewDecision.APPROVED);
    }

    @Test
    void rejectFailsWithoutTheNetwork() {
        action = "hold_for_review";
        Payment held = pay(UUID.randomUUID().toString(), 1_500_00);

        Payment rejected = paymentService.review(held.getId(), Payment.ReviewDecision.REJECTED, "admin@test.com", "stolen card");

        assertThat(rejected.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(rejected.getFailureReason()).isEqualTo("risk_review_rejected");
        assertThat(rejected.getReviewNote()).isEqualTo("stolen card");
        assertThat(processorCalls(held)).isZero();
        assertThat(events(held)).containsExactly("payment.held_for_review", "payment.failed");
    }

    @Test
    void onlyHeldPaymentsCanBeReviewed() {
        Payment authorized = pay(UUID.randomUUID().toString(), 1_500_00);
        assertThatThrownBy(() -> paymentService.review(authorized.getId(), Payment.ReviewDecision.REJECTED, "a", null))
                .isInstanceOf(IllegalPaymentStateException.class);
        assertThat(paymentService.get(authorized.getId()).getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
    }
}
