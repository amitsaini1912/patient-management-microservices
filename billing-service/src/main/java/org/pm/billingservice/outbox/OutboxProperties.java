package org.pm.billingservice.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * billing.outbox.* settings.
 *
 * @param batchSize       rows published per relay transaction
 * @param initialBackoff  pause after the first failed publish; doubles on every further failure ...
 * @param maxBackoff      ... up to this
 * @param retention       SENT rows older than this are deleted (they only matter for debugging / replay)
 */
@ConfigurationProperties("billing.outbox")
public record OutboxProperties(
        @DefaultValue("100") int batchSize,
        @DefaultValue("1s") Duration initialBackoff,
        @DefaultValue("60s") Duration maxBackoff,
        @DefaultValue("7d") Duration retention) {
}
