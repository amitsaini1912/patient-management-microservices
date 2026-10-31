package org.pm.billingservice.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Outbox lag for Prometheus (Day 20b, ADR 0020), read from the table on each scrape:
 *   billing_outbox_pending                       events written but not yet on Kafka
 *   billing_outbox_oldest_pending_age_seconds    how long the oldest of them has waited (0 when none) - the lag
 *   billing_outbox_failed                        events Kafka refused for good: need a human (ADR 0008)
 * Partial indexes keep each query small (PENDING / FAILED rows only). DB trouble -> NaN, never an exception.
 */
@Component
public class OutboxMetrics {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public OutboxMetrics(JdbcTemplate jdbc, Clock clock, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.clock = clock;
        Gauge.builder("billing.outbox.pending", this, m -> m.count("PENDING"))
                .description("Outbox events not yet published to Kafka").register(meters);
        Gauge.builder("billing.outbox.failed", this, m -> m.count("FAILED"))
                .description("Outbox events Kafka refused permanently").register(meters);
        Gauge.builder("billing.outbox.oldest.pending.age", this, OutboxMetrics::oldestPendingAgeSeconds)
                .baseUnit("seconds").description("Age of the oldest unpublished outbox event").register(meters);
    }

    private double count(String status) {
        try {
            Long n = jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE status = ?", Long.class, status);
            return n == null ? 0 : n;
        } catch (RuntimeException e) {
            log.debug("outbox gauge not read: {}", e.toString());
            return Double.NaN;
        }
    }

    private double oldestPendingAgeSeconds() {
        try {
            Timestamp oldest = jdbc.queryForObject("SELECT min(created_at) FROM outbox_event WHERE status = 'PENDING'",
                    Timestamp.class);
            return oldest == null ? 0 : Math.max(0, Duration.between(oldest.toInstant(), clock.instant()).toMillis() / 1000.0);
        } catch (RuntimeException e) {
            log.debug("outbox gauge not read: {}", e.toString());
            return Double.NaN;
        }
    }
}
