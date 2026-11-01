package org.pm.billingservice.risk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.pm.billingservice.payment.Payment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * POST /v2/assess on the risk engine (payment-risk-engine, its ADR 0001): billing sends FACTS, the engine computes
 * every risk feature itself. The payment id is the transaction_id, so a retry of the same payment gets the engine's
 * stored decision (its ADR 0003).
 *
 * The JDK HttpClient, not RestClient: the timeout must be set PER CALL (what is left of the 300 ms budget).
 */
@Component
@ConditionalOnProperty(name = "billing.risk.enabled", havingValue = "true")
public class HttpRiskEngineClient implements RiskEngineClient {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record Facts(String transactionId, long amountMinor, String currency, String cardToken, String cardFingerprint,
                 String last4, String customerId, String merchantId, String ip, String deviceId, String timestamp) {}

    // the engine's answer has ~25 fields; we keep the decision and what identifies how it was made
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record Answer(String action, List<String> reasonCodes, Double modelScore, String modelVersion, String rulesVersion) {}

    private final HttpClient http;
    private final ObjectMapper json;
    private final URI assessUri;
    private final URI readyUri;
    private final String apiKey;
    private final String merchantId;

    public HttpRiskEngineClient(RiskProperties properties, ObjectMapper json) {
        if (properties.baseUrl() == null || properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException("billing.risk.enabled=true needs billing.risk.base-url and api-key (env RISK_URL, RISK_API_KEY_BILLING)");
        }
        // HTTP/1.1: the default (HTTP/2) sends an h2c upgrade on the first request of each plain-http connection;
        // uvicorn speaks HTTP/1.1 only, so that is wasted work on exactly the cold requests the budget can't afford
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(properties.budget()).build();
        this.json = json;
        this.assessUri = properties.baseUrl().resolve("/v2/assess");
        this.readyUri = properties.baseUrl().resolve("/ready");
        this.apiKey = properties.apiKey();
        this.merchantId = properties.merchantId();
    }

    @Override
    public RiskVerdict assess(Payment payment, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder(assessUri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("X-API-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body(payment)))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new RiskEngineUnavailableException("timeout", "risk engine did not answer in " + timeout.toMillis() + " ms", false, e);
        } catch (IOException e) {
            throw new RiskEngineUnavailableException("unreachable", "risk engine unreachable: " + e, true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RiskEngineUnavailableException("interrupted", "interrupted while waiting for the risk engine", false, e);
        }
        int status = response.statusCode();
        if (status != 200) {
            // 502/503/504: the engine (or its Redis) is in trouble right now - one more try may work.
            // 401 (our key), 409 (same payment id, different facts), 422 (facts it refuses): the same request fails again.
            boolean retryable = status == 502 || status == 503 || status == 504;
            throw new RiskEngineUnavailableException(status == 409 ? "conflict" : "http_" + status,
                    "risk engine answered " + status, retryable, null);
        }
        return verdict(response.body());
    }

    /**
     * Startup warm-up (ADR 0026): {@code calls} GETs of the engine's /ready through THIS client (same connection pool,
     * same code path as /v2/assess up to the URL), plus one round of our JSON writing and reading. Sends no payment,
     * so the engine stores and counts nothing. Any HTTP answer counts (a 503 "not ready" still warmed the connection);
     * stops early when the engine can't be reached. Returns the answered calls' durations in ms.
     */
    public List<Long> warmUp(int calls, Duration timeout) {
        try {
            json.writeValueAsBytes(new Facts("warm-up", 1, "INR", "tok", "fp", "0000", "acct", merchantId, null, null,
                    "2026-01-01T00:00:00Z"));
            json.readValue(WARM_UP_ANSWER, Answer.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        List<Long> took = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            long start = System.nanoTime();
            try {
                http.send(HttpRequest.newBuilder(readyUri).timeout(timeout).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
            } catch (IOException e) {
                break; // engine not there: nothing to warm, the payments' fallback covers it
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            took.add((System.nanoTime() - start) / 1_000_000);
        }
        return took;
    }

    private static final byte[] WARM_UP_ANSWER = """
            {"action":"allow","reason_codes":["R00_NONE"],"model_score":0.1,"model_version":"m","rules_version":"r"}"""
            .getBytes(StandardCharsets.UTF_8);

    private byte[] body(Payment payment) {
        Facts facts = new Facts(payment.getId().toString(), payment.getAmount().amountMinor(),
                payment.getAmount().currency().getCurrencyCode(), payment.getCardToken(), payment.getCardFingerprint(),
                payment.getCardLast4(), payment.getBillingAccountId().toString(), merchantId,
                payment.getRiskClientIp(), payment.getRiskDeviceId(),
                // ms precision: a retry reloads the payment from Postgres (microseconds) - the string must be the
                // same as on the first attempt, or the engine sees different facts for the same id (409)
                payment.getCreatedAt().truncatedTo(ChronoUnit.MILLIS).toString());
        try {
            return json.writeValueAsBytes(facts);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private RiskVerdict verdict(byte[] body) {
        Answer answer;
        try {
            answer = json.readValue(body, Answer.class);
        } catch (IOException e) {
            throw new RiskEngineUnavailableException("bad_response", "risk engine answer is not valid JSON", false, e);
        }
        RiskDecision decision = switch (answer.action() == null ? "" : answer.action()) {
            case "allow" -> RiskDecision.ALLOW;
            case "hold_for_review" -> RiskDecision.HOLD;
            case "block" -> RiskDecision.BLOCK;
            default -> throw new RiskEngineUnavailableException("bad_response", "unknown risk action: " + answer.action(), false, null);
        };
        return new RiskVerdict(decision, RiskSource.ENGINE, answer.reasonCodes(), answer.modelScore(),
                answer.modelVersion(), answer.rulesVersion(), null);
    }
}
