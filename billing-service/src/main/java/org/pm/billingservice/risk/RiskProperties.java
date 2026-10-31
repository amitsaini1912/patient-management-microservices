package org.pm.billingservice.risk;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * billing.risk.* - the risk check before authorization (ADR 0017).
 *
 * @param enabled    false = billing's own rules only (the repo runs without the risk engine)
 * @param budget     total time the risk check may take, retry included; the payment then goes on with the fallback
 * @param minAttempt an attempt (first or retry) is only started with at least this much budget left
 * @param merchantId sent as merchant_id: the hospital is the merchant
 */
@ConfigurationProperties("billing.risk")
public record RiskProperties(
        @DefaultValue("false") boolean enabled,
        URI baseUrl,
        String apiKey,
        @DefaultValue("300ms") Duration budget,
        @DefaultValue("50ms") Duration minAttempt,
        @DefaultValue("hospital-main") String merchantId,
        @DefaultValue Fallback fallback,
        @DefaultValue CircuitBreaker circuitBreaker) {

    /**
     * Billing's own rules when the engine can't be used: amounts in BOOK currency minor units (INR paise).
     * Never BLOCK without the engine's evidence; big payments go to review instead of straight through.
     */
    public record Fallback(@DefaultValue("5000000") long holdAtBookMinor,          // INR 50,000.00
                           @DefaultValue("1000000") long foreignHoldAtBookMinor) {} // INR 10,000.00, card paid in USD/EUR

    /** Opens after {@code failureRate}% of the last {@code window} calls failed (at least {@code minimumCalls}). */
    public record CircuitBreaker(@DefaultValue("20") int window,
                                 @DefaultValue("10") int minimumCalls,
                                 @DefaultValue("50") float failureRate,
                                 @DefaultValue("30s") Duration openFor) {}
}
