package org.pm.billingservice.processor;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.UUID;
import org.pm.billingservice.money.Money;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * The real adapter: PaymentProcessor over HTTP to payment-network-simulator's acquirer API.
 *
 * Every call sends our idempotency key as the {@code Idempotency-Key} header, so a retry after a timeout or a crash
 * gets the acquirer's first answer instead of a second capture/refund (ADR 0007). Any failure - a refusal (4xx),
 * an acquirer error (5xx) or no answer at all - becomes a PaymentProcessorException (502 to our client).
 */
@Component
@ConditionalOnProperty(name = "billing.processor", havingValue = "simulator", matchIfMissing = true)
@EnableConfigurationProperties(SimulatorProperties.class)
public class SimulatorPaymentProcessor implements PaymentProcessor {

    private static final String KEY = "Idempotency-Key";

    private final RestClient http;

    public SimulatorPaymentProcessor(RestClient.Builder builder, SimulatorProperties properties) {
        if (properties.baseUrl() == null || properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException("billing.simulator.base-url and api-key must be set (env SIMULATOR_URL, SIMULATOR_API_KEY)");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        this.http = builder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .build();
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record AuthorizeRequest(String merchantReference, long amountMinor, String currency, String paymentMethodToken) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record AmountRequest(long amountMinor, String currency) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record AuthorizationResponse(String id, String status, String declineReason, String networkResponseCode,
                                 boolean retryable) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record RefundResponse(String id) {}

    @Override
    public AuthorizationResult authorize(String idempotencyKey, UUID paymentId, Money amount, String paymentMethodToken) {
        AuthorizationResponse response = call("authorize", () -> http.post().uri("/v1/authorizations")
                .header(KEY, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                // merchant_reference = our payment id: the acquirer echoes it in webhooks, so we can find the payment
                .body(new AuthorizeRequest(paymentId.toString(), amount.amountMinor(),
                        amount.currency().getCurrencyCode(), paymentMethodToken))
                .retrieve().body(AuthorizationResponse.class));
        return switch (response.status()) {
            case "AUTHORIZED" -> AuthorizationResult.approved(response.id(), response.networkResponseCode());
            // a 91 (issuer unavailable) is a decline too, with retryable=true: the payment ends FAILED, the client may
            // try again with a NEW Idempotency-Key (the same key would replay this FAILED payment)
            case "DECLINED" -> AuthorizationResult.declined(response.declineReason(), response.networkResponseCode(),
                    response.retryable());
            default -> throw new PaymentProcessorException("unexpected authorization status " + response.status());
        };
    }

    @Override
    public void capture(String idempotencyKey, String processorReference, Money amount) {
        call("capture", () -> http.post().uri("/v1/authorizations/{id}/capture", processorReference)
                .header(KEY, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new AmountRequest(amount.amountMinor(), amount.currency().getCurrencyCode()))
                .retrieve().toBodilessEntity());
    }

    @Override
    public void voidAuthorization(String idempotencyKey, String processorReference) {
        call("void", () -> http.post().uri("/v1/authorizations/{id}/void", processorReference)
                .header(KEY, idempotencyKey)
                .retrieve().toBodilessEntity());
    }

    @Override
    public String refund(String idempotencyKey, String processorReference, Money amount) {
        return call("refund", () -> http.post().uri("/v1/authorizations/{id}/refunds", processorReference)
                .header(KEY, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new AmountRequest(amount.amountMinor(), amount.currency().getCurrencyCode()))
                .retrieve().body(RefundResponse.class)).id();
    }

    private interface Call<T> {
        T run();
    }

    private static <T> T call(String operation, Call<T> call) {
        try {
            T result = call.run();
            if (result == null) {
                throw new PaymentProcessorException(operation + ": empty response from acquirer");
            }
            return result;
        } catch (RestClientResponseException e) {
            // 422 (amount_invalid: more than is left, e.g. a concurrent refund took the rest) -> our 409. 409
            // invalid_state (the acquirer's state differs from ours), 401 (our key), 5xx (acquirer broken) -> 502.
            String message = operation + " refused by acquirer: HTTP " + e.getStatusCode().value() + " " + e.getResponseBodyAsString();
            int status = e.getStatusCode().value();
            throw status == 422 ? PaymentProcessorException.refused(message, e)
                    : new PaymentProcessorException(message, e);
        } catch (RestClientException e) {
            // timeout / connection refused: outcome UNKNOWN. Safe to retry with the same key.
            throw new PaymentProcessorException(operation + ": no answer from acquirer (" + e.getMessage() + ")", e);
        }
    }
}
