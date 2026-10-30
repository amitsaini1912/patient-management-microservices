package org.pm.billingservice.ratelimit;

import java.time.Duration;

/** Too many authorization attempts with one card. Maps to 429 + Retry-After; nothing was charged. */
public class CardRateLimitedException extends RuntimeException {

    private final Duration retryAfter;

    public CardRateLimitedException(Duration retryAfter) {
        super("too many payment attempts with this card");
        this.retryAfter = retryAfter;
    }

    /** Whole seconds, rounded up, at least 1: the unit of the Retry-After header. */
    public long retryAfterSeconds() {
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }
}
