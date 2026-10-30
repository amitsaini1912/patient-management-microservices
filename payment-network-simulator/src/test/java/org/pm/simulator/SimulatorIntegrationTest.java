package org.pm.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pm.simulator.webhook.WebhookDispatcher;
import org.pm.simulator.webhook.WebhookMode;
import org.pm.simulator.webhook.WebhookSigner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The acquirer API through MockMvc, and its webhooks delivered over real HTTP to a tiny JDK HttpServer that
 * plays billing-service's endpoint (it can also answer 500 to test retries).
 */
@SpringBootTest(properties = {
        "simulator.api-key=test-api-key",
        "simulator.webhooks.secret=test-webhook-secret",
        "simulator.webhooks.delay=10ms",
        "simulator.webhooks.initial-backoff=50ms"})
@AutoConfigureMockMvc
class SimulatorIntegrationTest {

    private static final String API_KEY = "Bearer test-api-key";
    private static final String SECRET = "test-webhook-secret";
    private static final Duration WAIT = Duration.ofSeconds(10);

    private static final HttpServer receiver;
    private static final List<Received> received = new CopyOnWriteArrayList<>();
    private static final AtomicInteger failNext = new AtomicInteger();

    record Received(String signature, String body) {}

    static {
        try {
            receiver = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        receiver.createContext("/webhooks/gateway", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int status = failNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0 ? 500 : 200;
            if (status == 200) {
                received.add(new Received(exchange.getRequestHeaders().getFirst(WebhookSigner.HEADER), body));
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        receiver.start();
    }

    @DynamicPropertySource
    static void webhookTarget(DynamicPropertyRegistry registry) {
        registry.add("simulator.webhooks.target-url",
                () -> "http://localhost:" + receiver.getAddress().getPort() + "/webhooks/gateway");
    }

    @AfterAll
    static void stopReceiver() {
        receiver.stop(0);
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private WebhookDispatcher dispatcher;

    @BeforeEach
    void reset() {
        received.clear();
        failNext.set(0);
    }

    @AfterEach
    void normalMode() {
        dispatcher.setMode(WebhookMode.NORMAL);
    }

    // ---- API key ----

    @Test
    void callsWithoutTheRightApiKeyAre401HealthIsOpen() throws Exception {
        mvc.perform(post("/v1/authorizations").contentType(MediaType.APPLICATION_JSON).content(authorizeBody("p1", 100, "pm_card_visa")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/authorizations").header("Authorization", "Bearer wrong")
                        .contentType(MediaType.APPLICATION_JSON).content(authorizeBody("p1", 100, "pm_card_visa")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/webhooks")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    // ---- acquirer ----

    @Test
    void approvedAuthorizationSendsASignedAuthorizedWebhook() throws Exception {
        String reference = UUID.randomUUID().toString();

        authorize(newKey(), reference, 1_000_00, "pm_card_visa")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.authorized_minor").value(1_000_00))
                .andExpect(jsonPath("$.merchant_reference").value(reference));

        JsonNode event = awaitWebhook(reference, "payment.authorized");
        assertThat(event.get("id").asText()).startsWith("evt_");
        assertThat(event.at("/data/amount_minor").asLong()).isEqualTo(1_000_00);
        Received raw = rawOf(event.get("id").asText());
        // the receiver can recompute the signature from t + raw body with the shared secret
        String t = raw.signature().split(",")[0].substring(2);
        assertThat(raw.signature()).isEqualTo(WebhookSigner.header(SECRET, Long.parseLong(t), raw.body()));
        assertThat(Long.parseLong(t)).isCloseTo(Instant.now().getEpochSecond(), org.assertj.core.data.Offset.offset(60L));
    }

    @Test
    void declinedCardIs200WithDeclinedStatusAndAFailedWebhook() throws Exception {
        String reference = UUID.randomUUID().toString();

        authorize(newKey(), reference, 500_00, "pm_card_declined")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.decline_reason").value("card_declined"));

        assertThat(awaitWebhook(reference, "payment.failed").at("/data/decline_reason").asText()).isEqualTo("card_declined");
    }

    @Test
    void sameIdempotencyKeyReturnsTheSameAuthorizationAndSendsOneWebhook() throws Exception {
        String key = newKey();
        String reference = UUID.randomUUID().toString();

        String first = authorize(key, reference, 1_000_00, "pm_card_visa").andReturn().getResponse().getContentAsString();
        String second = authorize(key, reference, 1_000_00, "pm_card_visa").andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        awaitWebhook(reference, "payment.authorized");
        Thread.sleep(200); // a second webhook, if any, would be here by now
        assertThat(webhooksFor(reference)).hasSize(1);
    }

    @Test
    void sameKeyDifferentRequestIs422MissingKeyIs400() throws Exception {
        String key = newKey();
        authorize(key, "p-" + UUID.randomUUID(), 1_000_00, "pm_card_visa").andExpect(status().isOk());

        authorize(key, "p-" + UUID.randomUUID(), 2_000_00, "pm_card_visa")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("idempotency_key_reused"));
        mvc.perform(post("/v1/authorizations").header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(authorizeBody("p", 100, "pm_card_visa")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("idempotency_key_missing"));
    }

    @Test
    void captureVoidRefundFollowTheRules() throws Exception {
        String reference = UUID.randomUUID().toString();
        String auth = authorizationId(authorize(newKey(), reference, 1_000_00, "pm_card_visa"));

        call("/v1/authorizations/" + auth + "/capture", "{\"amount_minor\":100001,\"currency\":\"INR\"}")
                .andExpect(status().isUnprocessableEntity());                                  // more than held
        call("/v1/authorizations/" + auth + "/capture", "{\"amount_minor\":60000,\"currency\":\"USD\"}")
                .andExpect(jsonPath("$.error.code").value("currency_mismatch"));
        call("/v1/authorizations/" + auth + "/capture", "{\"amount_minor\":60000,\"currency\":\"INR\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.captured_minor").value(60000));
        call("/v1/authorizations/" + auth + "/capture", "{\"amount_minor\":60000,\"currency\":\"INR\"}")
                .andExpect(status().isConflict());                                             // new key, already captured
        call("/v1/authorizations/" + auth + "/void", "")
                .andExpect(status().isConflict());
        call("/v1/authorizations/" + auth + "/refunds", "{\"amount_minor\":60001,\"currency\":\"INR\"}")
                .andExpect(status().isUnprocessableEntity());                                  // more than captured
        call("/v1/authorizations/" + auth + "/refunds",
                "{\"amount_minor\":20000,\"currency\":\"INR\",\"merchant_refund_reference\":\"our-refund-1\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.startsWith("re_")))
                .andExpect(jsonPath("$.authorization.refunded_minor").value(20000));

        JsonNode refunded = awaitWebhook(reference, "payment.refunded");
        assertThat(refunded.at("/data/merchant_refund_reference").asText()).isEqualTo("our-refund-1");
        assertThat(refunded.at("/data/refund_amount_minor").asLong()).isEqualTo(20000);
        call("/v1/authorizations/auth_nope/capture", "{\"amount_minor\":1,\"currency\":\"INR\"}")
                .andExpect(status().isNotFound());
    }

    // ---- settlement report (Day 8) ----

    @Test
    void settlementReportListsTodaysCapturesAndRefundsByUtcDay() throws Exception {
        String reference = UUID.randomUUID().toString();
        String auth = authorizationId(authorize(newKey(), reference, 1_000_00, "pm_card_visa"));
        call("/v1/authorizations/" + auth + "/capture", "{\"amount_minor\":100000,\"currency\":\"INR\"}");
        String refundId = objectMapper.readTree(call("/v1/authorizations/" + auth + "/refunds",
                "{\"amount_minor\":30000,\"currency\":\"INR\"}").andReturn().getResponse().getContentAsString()).get("id").asText();
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);

        JsonNode report = settlementReport(today);
        List<JsonNode> mine = rowsFor(report, reference);
        assertThat(mine).extracting(r -> r.get("type").asText()).containsExactly("CAPTURE", "REFUND");
        assertThat(mine.get(0).get("id").asText()).startsWith("cap_");
        assertThat(mine.get(0).get("amount_minor").asLong()).isEqualTo(1_000_00);
        assertThat(mine.get(1).get("id").asText()).isEqualTo(refundId); // the id the merchant got back
        assertThat(mine.get(1).get("authorization_id").asText()).isEqualTo(auth);
        assertThat(rowsFor(settlementReport(today.minusDays(1)), reference)).isEmpty(); // UTC day cut-off

        // test knob: the acquirer's books now say 999.00 for that capture
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                                "/admin/settlements/" + mine.get(0).get("id").asText())
                        .header("Authorization", API_KEY).contentType(MediaType.APPLICATION_JSON).content("{\"amount_minor\":99900}"))
                .andExpect(status().isOk());
        assertThat(rowsFor(settlementReport(today), reference).get(0).get("amount_minor").asLong()).isEqualTo(99900);
        mvc.perform(get("/v1/settlements/" + today)).andExpect(status().isUnauthorized());
    }

    private JsonNode settlementReport(java.time.LocalDate date) throws Exception {
        return objectMapper.readTree(mvc.perform(get("/v1/settlements/" + date).header("Authorization", API_KEY))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static List<JsonNode> rowsFor(JsonNode report, String reference) {
        List<JsonNode> rows = new java.util.ArrayList<>();
        report.get("rows").forEach(r -> {
            if (reference.equals(r.get("merchant_reference").asText())) {
                rows.add(r);
            }
        });
        return rows;
    }

    // ---- webhook delivery ----

    @Test
    void aFailedWebhookIsRetriedWithTheSameEventId() throws Exception {
        failNext.set(2); // the merchant answers 500 twice
        String reference = UUID.randomUUID().toString();

        authorize(newKey(), reference, 100_00, "pm_card_visa");

        JsonNode event = awaitWebhook(reference, "payment.authorized");
        WebhookDispatcher.Delivery delivery = dispatcher.deliveries().stream()
                .filter(d -> d.event().id().equals(event.get("id").asText())).findFirst().orElseThrow();
        assertThat(delivery.attempts()).isEqualTo(3);
        assertThat(delivery.delivered()).isTrue();
    }

    @Test
    void duplicateModeDeliversEveryEventTwiceWithOneId() throws Exception {
        dispatcher.setMode(WebhookMode.DUPLICATE);
        String reference = UUID.randomUUID().toString();

        authorize(newKey(), reference, 100_00, "pm_card_visa");

        await(() -> webhooksFor(reference).size() == 2);
        assertThat(webhooksFor(reference)).extracting(e -> e.get("id").asText()).containsOnly(
                webhooksFor(reference).get(0).get("id").asText());
    }

    @Test
    void holdThenReversedFlushDeliversOutOfOrder() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/admin/webhooks/mode")
                        .header("Authorization", API_KEY).contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"HOLD\"}"))
                .andExpect(status().isOk());
        String reference = UUID.randomUUID().toString();
        String auth = authorizationId(authorize(newKey(), reference, 1_000_00, "pm_card_visa"));
        call("/v1/authorizations/" + auth + "/capture", "{\"amount_minor\":100000,\"currency\":\"INR\"}");
        Thread.sleep(200);
        assertThat(webhooksFor(reference)).isEmpty(); // held

        mvc.perform(post("/admin/webhooks/flush").param("reversed", "true").header("Authorization", API_KEY))
                .andExpect(jsonPath("$.flushed").value(2));

        await(() -> webhooksFor(reference).size() == 2);
        assertThat(webhooksFor(reference)).extracting(e -> e.get("type").asText())
                .containsExactly("payment.captured", "payment.authorized");
    }

    // ---- helpers ----

    private ResultActions authorize(String key, String reference, long amountMinor, String token) throws Exception {
        return mvc.perform(post("/v1/authorizations").header("Authorization", API_KEY).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(authorizeBody(reference, amountMinor, token)));
    }

    private ResultActions call(String path, String body) throws Exception {
        return mvc.perform(post(path).header("Authorization", API_KEY).header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String authorizationId(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private static String authorizeBody(String reference, long amountMinor, String token) {
        return """
                {"merchant_reference":"%s","amount_minor":%d,"currency":"INR","payment_method_token":"%s"}"""
                .formatted(reference, amountMinor, token);
    }

    private List<JsonNode> webhooksFor(String reference) {
        return received.stream().map(r -> parse(r.body()))
                .filter(e -> reference.equals(e.at("/data/merchant_reference").asText())).toList();
    }

    private JsonNode awaitWebhook(String reference, String type) throws InterruptedException {
        await(() -> webhooksFor(reference).stream().anyMatch(e -> type.equals(e.get("type").asText())));
        return webhooksFor(reference).stream().filter(e -> type.equals(e.get("type").asText())).findFirst().orElseThrow();
    }

    private Received rawOf(String eventId) {
        return received.stream().filter(r -> eventId.equals(parse(r.body()).get("id").asText())).findFirst().orElseThrow();
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met within " + WAIT);
            }
            Thread.sleep(20);
        }
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }
}
