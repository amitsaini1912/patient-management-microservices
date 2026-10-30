package org.pm.billingservice.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * billing.rate-limit.card.* - how many authorization attempts one card gets (ADR 0015).
 *
 * @param capacity    attempts in a burst (a full bucket)
 * @param refillEvery one attempt comes back per this much time
 */
@ConfigurationProperties("billing.rate-limit.card")
public record CardRateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("5") int capacity,
        @DefaultValue("1m") Duration refillEvery) {

    public CardRateLimitProperties {
        if (capacity < 1 || refillEvery.isNegative() || refillEvery.isZero()) {
            throw new IllegalArgumentException("billing.rate-limit.card: capacity >= 1 and refill-every > 0 required");
        }
    }
}
