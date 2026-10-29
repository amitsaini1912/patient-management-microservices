package org.pm.billingservice.payment;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * billing.payments.* settings.
 *
 * @param authorizationTtl how long an uncaptured authorization lives before the expiry job expires it.
 *                         Card networks let issuers drop holds after ~7 days, so capturing later is unsafe.
 * @param expiryBatchSize  how many payments one expiry transaction claims (keeps transactions and lock lists short)
 */
@ConfigurationProperties("billing.payments")
public record PaymentProperties(
        @DefaultValue("7d") Duration authorizationTtl,
        @DefaultValue("100") int expiryBatchSize) {
}
