package org.pm.billingservice.processor;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.money.Money;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The adapter's side of the HTTP contract with payment-network-simulator, against a stub acquirer: what it sends
 * (path, API key, Idempotency-Key, snake_case body) and how it maps answers and failures.
 */
class SimulatorPaymentProcessorTest {

    record Call(String method, String path, String authorization, String idempotencyKey, String body) {}

    private HttpServer acquirer;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String response = "{}";
    private volatile long delayMs = 0;
    private SimulatorPaymentProcessor adapter;

    @BeforeEach
    void start() throws IOException {
        acquirer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        acquirer.createContext("/", exchange -> {
            calls.add(new Call(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"),
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
        acquirer.start();
        adapter = adapter(URI.create("http://localhost:" + acquirer.getAddress().getPort()), Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() {
        acquirer.stop(0);
    }

    @Test
    void authorizeSendsKeyApiKeyAndOurPaymentIdAsMerchantReference() {
        UUID paymentId = UUID.randomUUID();
        response = "{\"id\":\"auth_123\",\"status\":\"AUTHORIZED\",\"authorized_minor\":100000}";

        AuthorizationResult result = adapter.authorize("authorize:" + paymentId, paymentId, inr(1_000_00), "pm_card_visa");

        assertThat(result).isEqualTo(AuthorizationResult.approved("auth_123"));
        Call call = calls.get(0);
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.path()).isEqualTo("/v1/authorizations");
        assertThat(call.authorization()).isEqualTo("Bearer sim-key");
        assertThat(call.idempotencyKey()).isEqualTo("authorize:" + paymentId);
        assertThat(call.body()).contains("\"merchant_reference\":\"" + paymentId + "\"", "\"amount_minor\":100000",
                "\"currency\":\"INR\"", "\"payment_method_token\":\"pm_card_visa\"");
    }

    @Test
    void declineIsAResultNotAnError() {
        response = "{\"id\":\"auth_9\",\"status\":\"DECLINED\",\"decline_reason\":\"card_declined\"}";

        assertThat(adapter.authorize("k", UUID.randomUUID(), inr(100), "pm_card_declined"))
                .isEqualTo(AuthorizationResult.declined("card_declined"));
    }

    @Test
    void captureVoidRefundHitTheRightPaths() {
        response = "{\"id\":\"re_1\"}";

        adapter.capture("capture-key", "auth_1", inr(600_00));
        adapter.voidAuthorization("void-key", "auth_2");
        String refundId = adapter.refund("refund-key", "auth_3", inr(100_00));

        assertThat(calls).extracting(Call::path)
                .containsExactly("/v1/authorizations/auth_1/capture", "/v1/authorizations/auth_2/void", "/v1/authorizations/auth_3/refunds");
        assertThat(calls).extracting(Call::idempotencyKey).containsExactly("capture-key", "void-key", "refund-key");
        assertThat(calls.get(0).body()).contains("\"amount_minor\":60000");
        assertThat(refundId).isEqualTo("re_1");
    }

    @Test
    void refusalsAndAcquirerErrorsBecomeProcessorExceptions() {
        status = 409;
        response = "{\"error\":{\"code\":\"invalid_state\",\"message\":\"already captured\"}}";
        assertThatThrownBy(() -> adapter.capture("k", "auth_1", inr(100)))
                .isInstanceOf(PaymentProcessorException.class)
                .hasMessageContaining("409").hasMessageContaining("invalid_state");

        status = 503;
        assertThatThrownBy(() -> adapter.voidAuthorization("k", "auth_1")).isInstanceOf(PaymentProcessorException.class);
    }

    @Test
    void noAnswerIsAProcessorExceptionNotADecline() {
        // a timeout means "unknown outcome": the client gets 502 and retries with the same key
        delayMs = 1_500;
        SimulatorPaymentProcessor impatient = adapter(URI.create("http://localhost:" + acquirer.getAddress().getPort()),
                Duration.ofMillis(300));
        assertThatThrownBy(() -> impatient.capture("k", "auth_1", inr(100)))
                .isInstanceOf(PaymentProcessorException.class).hasMessageContaining("no answer");

        SimulatorPaymentProcessor nobodyHome = adapter(URI.create("http://localhost:1"), Duration.ofSeconds(1));
        assertThatThrownBy(() -> nobodyHome.authorize("k", UUID.randomUUID(), inr(100), "pm_card_visa"))
                .isInstanceOf(PaymentProcessorException.class);
    }

    private static SimulatorPaymentProcessor adapter(URI baseUrl, Duration readTimeout) {
        return new SimulatorPaymentProcessor(RestClient.builder(),
                new SimulatorProperties(baseUrl, "sim-key", Duration.ofSeconds(1), readTimeout));
    }

    private static Money inr(long paise) {
        return Money.ofMinor(paise, "INR");
    }
}
