package org.pm.billingservice.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.TestcontainersConfiguration;
import org.pm.billingservice.ledger.LedgerService;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.payment.Payment;
import org.pm.billingservice.payment.PaymentRepository;
import org.pm.billingservice.payment.PaymentService;
import org.pm.billingservice.payment.PaymentStatus;
import org.pm.billingservice.processor.FakePaymentProcessor;
import org.pm.billingservice.service.BillingAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POST /webhooks/gateway over real HTTP. Webhooks are built and signed here exactly like the simulator does
 * (secret "test-webhook-secret" from src/test/resources/config/application.properties).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "grpc.server.port=0")
@Import(TestcontainersConfiguration.class)
class GatewayWebhookIntegrationTest {

    private static final String SECRET = "test-webhook-secret";

    @LocalServerPort private int port;
    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private BillingAccountService billingAccountService;
    @Autowired private LedgerService ledgerService;
    @Autowired private FakePaymentProcessor processor;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();
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

    // ---- authenticity ----

    @Test
    void badSignatureIs401AndNothingIsStored() throws Exception {
        Payment payment = authorized();
        String body = webhook("payment.captured", payment, 1_000_00);

        HttpResponse<String> response = post(body, WebhookSignatureVerifierTest.header("wrong-secret", now(), bytes(body)));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(rowsFor(eventId(body))).isZero();
        assertThat(status(payment)).isEqualTo(PaymentStatus.AUTHORIZED);
    }

    @Test
    void oldTimestampIs401EvenWithACorrectSignature() throws Exception {
        Payment payment = authorized();
        String body = webhook("payment.captured", payment, 1_000_00);
        long sixMinutesAgo = now() - 6 * 60;

        // a real, correctly signed webhook - recorded by an attacker and replayed 6 minutes later
        HttpResponse<String> response = post(body, WebhookSignatureVerifierTest.header(SECRET, sixMinutesAgo, bytes(body)));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(status(payment)).isEqualTo(PaymentStatus.AUTHORIZED);
    }

    @Test
    void missingSignatureIs401() throws Exception {
        assertThat(post(webhook("payment.captured", authorized(), 1_000_00), null).statusCode()).isEqualTo(401);
    }

    // ---- healing the dual-write gap ----

    @Test
    void capturedWebhookCatchesUpAPaymentWhoseOwnCaptureWasNeverRecorded() throws Exception {
        // the acquirer captured, but our step 3 never committed (crash): we still say AUTHORIZED
        Payment payment = authorized();
        processor.capture("capture:" + payment.getId() + ":100000:INR", payment.getProcessorReference(), inr(1_000_00));
        String body = webhook("payment.captured", payment, 1_000_00);

        HttpResponse<String> response = signedPost(body);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("APPLIED");
        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(after.getCapturedAmount()).isEqualTo(inr(1_000_00));
        // the full capture: ledger posted (patient owes nothing, it's paid by card) and the event is in the outbox
        assertThat(ledgerService.balanceOf(ledgerService.patientReceivable(billingAccountId))).isEqualTo(inr(0));
        assertThat(outboxEvents(payment, "payment.captured")).isEqualTo(1);
        assertThat(outcome(eventId(body))).isEqualTo("APPLIED");
    }

    @Test
    void duplicateWebhookIs200AndChangesNothing() throws Exception {
        Payment payment = authorized();
        String body = webhook("payment.captured", payment, 1_000_00);

        HttpResponse<String> first = signedPost(body);
        HttpResponse<String> second = signedPost(body); // same event id, e.g. the acquirer's retry

        assertThat(first.body()).contains("APPLIED");
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.body()).contains("DUPLICATE");
        assertThat(rowsFor(eventId(body))).isEqualTo(1);
        assertThat(outboxEvents(payment, "payment.captured")).isEqualTo(1); // captured (and posted) once
    }

    @Test
    void outOfOrderOldEventCannotMovePaymentBackwards() throws Exception {
        Payment payment = authorized();
        paymentService.capture(payment.getId(), null); // we're CAPTURED
        String lateAuthorized = webhook("payment.authorized", payment, 0);
        String lateFailed = webhook("payment.failed", payment, 0);

        HttpResponse<String> a = signedPost(lateAuthorized);
        HttpResponse<String> f = signedPost(lateFailed);

        assertThat(a.statusCode()).isEqualTo(200);
        assertThat(a.body()).contains("IGNORED_STALE");
        assertThat(f.body()).contains("IGNORED_STALE");
        assertThat(status(payment)).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    void eventForAStepWeHaventReachedIs409SoTheAcquirerRetries() throws Exception {
        // "captured" overtook "authorized": we still have the payment as CREATED
        UUID paymentId = UUID.randomUUID();
        paymentRepository.save(Payment.create(paymentId, billingAccountId, inr(1_000_00)));
        String captured = webhook("payment.captured", paymentId, "auth_x", 1_000_00);

        HttpResponse<String> early = signedPost(captured);

        assertThat(early.statusCode()).isEqualTo(409);
        assertThat(rowsFor(eventId(captured))).isZero(); // not stored, so the retry isn't a "duplicate"

        // the late "authorized" arrives, then the acquirer retries "captured": both apply, in the right order
        assertThat(signedPost(webhook("payment.authorized", paymentId, "auth_x", 0)).body()).contains("APPLIED");
        assertThat(signedPost(captured).body()).contains("APPLIED");
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    void failedWebhookRecordsTheNetworkCodeAndTheRetryableFlag() throws Exception {
        // our step 3 never ran; the acquirer's webhook tells us the issuer was unavailable (91)
        UUID paymentId = UUID.randomUUID();
        paymentRepository.save(Payment.create(paymentId, billingAccountId, inr(1_000_00)));
        Map<String, Object> event = objectMapper.readValue(webhook("payment.failed", paymentId, null, 0), Map.class);
        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) event.get("data");
        data.put("decline_reason", "issuer_unavailable");
        data.put("network_response_code", "91");
        data.put("retryable", true);

        assertThat(signedPost(objectMapper.writeValueAsString(event)).body()).contains("APPLIED");

        Payment after = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(after.getFailureReason()).isEqualTo("issuer_unavailable");
        assertThat(after.getNetworkResponseCode()).isEqualTo("91");
        assertThat(after.isRetryable()).isTrue();
    }

    @Test
    void mismatchedOrUnknownEventsAreAcknowledgedButNotApplied() throws Exception {
        Payment payment = authorized();
        String wrongAuthorization = webhook("payment.captured", payment.getId(), "auth_someone_else", 1_000_00);
        String unknownPayment = webhook("payment.captured", UUID.randomUUID(), "auth_x", 1_000_00);
        String refunded = webhook("payment.refunded", payment, 0);

        assertThat(signedPost(wrongAuthorization).body()).contains("IGNORED_MISMATCH");
        assertThat(signedPost(unknownPayment).body()).contains("IGNORED_UNKNOWN_PAYMENT");
        assertThat(signedPost(refunded).body()).contains("RECORDED");
        assertThat(status(payment)).isEqualTo(PaymentStatus.AUTHORIZED);
    }

    @Test
    void webhookThatBeatsOurOwnStep3DoesNotBreakTheCapture() throws Exception {
        // the realistic race: the acquirer's webhook arrives while our capture request is still between
        // "processor said yes" and "recorded it". Our step 3 must find it applied and return success - not 409.
        Payment payment = authorized();
        processor.setLatency(Duration.ofMillis(500));

        CompletableFuture<Payment> ourCapture = CompletableFuture.supplyAsync(() -> paymentService.capture(payment.getId(), null));
        Thread.sleep(100); // our request is now waiting on the "acquirer" (the webhook needs no processor call)
        assertThat(signedPost(webhook("payment.captured", payment, 1_000_00)).body()).contains("APPLIED");

        assertThat(ourCapture.get().getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(outboxEvents(payment, "payment.captured")).isEqualTo(1); // recorded once, by the webhook
        assertThat(ledgerService.balanceOf(ledgerService.patientReceivable(billingAccountId))).isEqualTo(inr(0));
    }

    // ---- helpers ----

    private Payment authorized() {
        return paymentService.authorize(UUID.randomUUID().toString(), billingAccountId, inr(1_000_00), "pm_card_visa");
    }

    private String webhook(String type, Payment payment, long capturedMinor) throws Exception {
        return webhook(type, payment.getId(), payment.getProcessorReference(), capturedMinor);
    }

    /** The simulator's format (AcquirerService.publish). */
    private String webhook(String type, UUID paymentId, String authorizationId, long capturedMinor) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("authorization_id", authorizationId);
        data.put("merchant_reference", paymentId.toString());
        data.put("status", "X");
        data.put("currency", "INR");
        data.put("amount_minor", 1_000_00);
        data.put("captured_minor", capturedMinor);
        data.put("refunded_minor", 0);
        if (type.equals("payment.failed")) {
            data.put("decline_reason", "card_declined");
        }
        return objectMapper.writeValueAsString(Map.of(
                "id", "evt_" + UUID.randomUUID(), "type", type, "created", now(), "data", data));
    }

    private HttpResponse<String> signedPost(String body) throws Exception {
        return post(body, WebhookSignatureVerifierTest.header(SECRET, now(), bytes(body)));
    }

    private HttpResponse<String> post(String body, String signature) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/webhooks/gateway"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (signature != null) {
            request.header("Sim-Signature", signature);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String eventId(String body) throws Exception {
        return objectMapper.readTree(body).get("id").asText();
    }

    private int rowsFor(String eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM webhook_event WHERE id = ?", Integer.class, eventId);
    }

    private String outcome(String eventId) {
        return jdbc.queryForObject("SELECT outcome FROM webhook_event WHERE id = ?", String.class, eventId);
    }

    private int outboxEvents(Payment payment, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = ?",
                Integer.class, payment.getId(), type);
    }

    private PaymentStatus status(Payment payment) {
        return paymentRepository.findById(payment.getId()).orElseThrow().getStatus();
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static Money inr(long paise) {
        return Money.ofMinor(paise, "INR");
    }
}
