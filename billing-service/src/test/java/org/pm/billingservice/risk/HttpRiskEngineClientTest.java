package org.pm.billingservice.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.pm.billingservice.payment.Payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Billing's side of the POST /v2/assess contract (razorpay-risk-agent ADR 0001), against a stub engine. */
class HttpRiskEngineClientTest {

    record Call(String path, String apiKey, String body) {}

    private static final String ALLOW = """
            {"api_version":"v2","transaction_id":"x","replayed":false,"action":"allow","reason_codes":["R00_NONE"],
             "model_score":0.0712,"model_version":"rf-b677801468","rules_version":"a78f6a4509d7","signals":{}}""";

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer engine;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String response = ALLOW;
    private volatile long delayMs = 0;
    private HttpRiskEngineClient client;

    @BeforeEach
    void start() throws IOException {
        engine = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        engine.createContext("/", exchange -> {
            calls.add(new Call(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("X-API-Key"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        engine.start();
        client = client(URI.create("http://localhost:" + engine.getAddress().getPort()));
    }

    @AfterEach
    void stop() {
        engine.stop(0);
    }

    @Test
    void sendsFactsWithTheApiKeyAndReadsTheVerdict() throws Exception {
        Payment payment = RiskTestPayments.inr(1_500_00);

        RiskVerdict verdict = client.assess(payment, Duration.ofMillis(300));

        assertThat(verdict).isEqualTo(new RiskVerdict(RiskDecision.ALLOW, RiskSource.ENGINE, List.of("R00_NONE"), 0.0712,
                "rf-b677801468", "a78f6a4509d7", null));
        Call call = calls.get(0);
        assertThat(call.path()).isEqualTo("/v2/assess");
        assertThat(call.apiKey()).isEqualTo("billing-test-key-0001");
        JsonNode facts = json.readTree(call.body());
        assertThat(facts.get("transaction_id").asText()).isEqualTo(payment.getId().toString());
        assertThat(facts.get("amount_minor").asLong()).isEqualTo(1_500_00);
        assertThat(facts.get("amount_minor").isIntegralNumber()).isTrue();          // never a float
        assertThat(facts.get("currency").asText()).isEqualTo("INR");
        assertThat(facts.get("card_token").asText()).isEqualTo("tok_test_card");
        assertThat(facts.get("card_fingerprint").asText()).isEqualTo(RiskTestPayments.FINGERPRINT);
        assertThat(facts.get("last4").asText()).isEqualTo("4242");
        assertThat(facts.get("customer_id").asText()).isEqualTo(payment.getBillingAccountId().toString());
        assertThat(facts.get("merchant_id").asText()).isEqualTo("hospital-main");
        assertThat(facts.get("ip").asText()).isEqualTo("203.0.113.9");
        assertThat(facts.get("device_id").asText()).isEqualTo("dev-1");
        assertThat(facts.get("timestamp").asText()).isEqualTo(payment.getCreatedAt().toString());
        assertThat(facts.size()).isEqualTo(11);                                    // the engine rejects extra fields
    }

    @ParameterizedTest
    @CsvSource({"hold_for_review, HOLD", "block, BLOCK", "allow, ALLOW"})
    void mapsEveryAction(String action, RiskDecision expected) {
        response = "{\"action\":\"" + action + "\",\"reason_codes\":[],\"model_score\":null,\"rules_version\":\"r\"}";
        RiskVerdict verdict = client.assess(RiskTestPayments.inr(100_00), Duration.ofMillis(300));
        assertThat(verdict.decision()).isEqualTo(expected);
        assertThat(verdict.score()).isNull();                                       // rules_only: no model score
    }

    @ParameterizedTest
    @CsvSource({"503, http_503, true", "502, http_502, true", "504, http_504, true", "401, http_401, false",
                "409, conflict, false", "422, http_422, false", "500, http_500, false"})
    void errorsSayWhetherARetryCanHelp(int code, String reason, boolean retryable) {
        status = code;
        response = "{\"detail\":\"x\"}";
        assertThatThrownBy(() -> client.assess(RiskTestPayments.inr(100_00), Duration.ofMillis(300)))
                .isInstanceOfSatisfying(RiskEngineUnavailableException.class, e -> {
                    assertThat(e.reason()).isEqualTo(reason);
                    assertThat(e.retryable()).isEqualTo(retryable);
                });
    }

    @Test
    void aSlowEngineIsATimeoutAfterTheGivenTimeNotLater() {
        delayMs = 1_000;
        long started = System.nanoTime();
        assertThatThrownBy(() -> client.assess(RiskTestPayments.inr(100_00), Duration.ofMillis(150)))
                .isInstanceOfSatisfying(RiskEngineUnavailableException.class, e -> {
                    assertThat(e.reason()).isEqualTo("timeout");
                    assertThat(e.retryable()).isFalse();
                });
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(600));
    }

    @Test
    void nobodyListeningIsRetryable() throws IOException {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        assertThatThrownBy(() -> client(URI.create("http://localhost:" + closed)).assess(RiskTestPayments.inr(100_00), Duration.ofMillis(300)))
                .isInstanceOfSatisfying(RiskEngineUnavailableException.class, e -> {
                    assertThat(e.reason()).isEqualTo("unreachable");
                    assertThat(e.retryable()).isTrue();
                });
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"not json", "{\"action\":\"maybe\"}", "{}"})
    void anAnswerWeCantReadIsNotUsed(String body) {
        response = body;
        assertThatThrownBy(() -> client.assess(RiskTestPayments.inr(100_00), Duration.ofMillis(300)))
                .isInstanceOfSatisfying(RiskEngineUnavailableException.class, e -> assertThat(e.reason()).isEqualTo("bad_response"));
    }

    @Test
    void enabledWithoutUrlOrKeyFailsAtStartup() {
        RiskProperties noKey = new RiskProperties(true, URI.create("http://localhost:1"), " ", Duration.ofMillis(300),
                Duration.ofMillis(50), "hospital-main", null, null);
        assertThatThrownBy(() -> new HttpRiskEngineClient(noKey, json)).isInstanceOf(IllegalStateException.class);
    }

    private HttpRiskEngineClient client(URI base) {
        RiskProperties p = RiskAssessorTest.properties(true);
        return new HttpRiskEngineClient(new RiskProperties(true, base, p.apiKey(), p.budget(), p.minAttempt(),
                p.merchantId(), p.fallback(), p.circuitBreaker()), json);
    }
}
