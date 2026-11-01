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
 * @param staleCreatedAfter a payment still CREATED this long after it was saved has an unknown outcome (the client
 *                          gave up, or we crashed mid-call): the resolver asks the acquirer. Far longer than any
 *                          request (risk 300 ms + acquirer timeouts), so no live request is still working on it.
 * @param staleBatchSize   how many stale payments one resolver run looks at
 */
@ConfigurationProperties("billing.payments")
public record PaymentProperties(
        @DefaultValue("7d") Duration authorizationTtl,
        @DefaultValue("100") int expiryBatchSize,
        @DefaultValue("15m") Duration staleCreatedAfter,
        @DefaultValue("100") int staleBatchSize) {
}
