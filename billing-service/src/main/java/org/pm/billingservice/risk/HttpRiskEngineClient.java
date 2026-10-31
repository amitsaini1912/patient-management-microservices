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
import java.time.Duration;
import java.util.List;
import org.pm.billingservice.payment.Payment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * POST /v2/assess on the risk engine (razorpay-risk-agent, its ADR 0001): billing sends FACTS, the engine computes
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
    private final String apiKey;
    private final String merchantId;

    public HttpRiskEngineClient(RiskProperties properties, ObjectMapper json) {
        if (properties.baseUrl() == null || properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException("billing.risk.enabled=true needs billing.risk.base-url and api-key (env RISK_URL, RISK_API_KEY_BILLING)");
        }
        this.http = HttpClient.newBuilder().connectTimeout(properties.budget()).build();
        this.json = json;
        this.assessUri = properties.baseUrl().resolve("/v2/assess");
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

    private byte[] body(Payment payment) {
        Facts facts = new Facts(payment.getId().toString(), payment.getAmount().amountMinor(),
                payment.getAmount().currency().getCurrencyCode(), payment.getCardToken(), payment.getCardFingerprint(),
                payment.getCardLast4(), payment.getBillingAccountId().toString(), merchantId,
                payment.getRiskClientIp(), payment.getRiskDeviceId(), payment.getCreatedAt().toString());
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
