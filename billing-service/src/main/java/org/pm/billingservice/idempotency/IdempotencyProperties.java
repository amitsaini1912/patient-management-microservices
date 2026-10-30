package org.pm.billingservice.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * billing.idempotency.* settings.
 *
 * @param ttl         how long a key is remembered; a retry after this is treated as a new request (Stripe: 24h)
 * @param lockTimeout an IN_PROGRESS key older than this is considered abandoned (the owner crashed) and a retry
 *                    may take it over. Must be longer than the slowest request, incl. processor timeouts.
 */
@ConfigurationProperties("billing.idempotency")
public record IdempotencyProperties(
        @DefaultValue("24h") Duration ttl,
        @DefaultValue("60s") Duration lockTimeout) {
}
