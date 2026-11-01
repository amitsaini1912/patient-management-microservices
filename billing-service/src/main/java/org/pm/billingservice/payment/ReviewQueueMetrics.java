package org.pm.billingservice.payment;

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
 * The manual-review queue for Prometheus (ADR 0034), read from the table on each scrape (partial index, small):
 *   billing_payments_pending_review                      payments held by the risk check, waiting for a person
 *   billing_payments_pending_review_oldest_age_seconds   how long the oldest has waited (0 when none)
 * A held payment has NOT been sent to the card network: the patient is waiting, nothing is reserved on the card.
 * DB trouble -> NaN, never an exception (like OutboxMetrics).
 */
@Component
public class ReviewQueueMetrics {

    private static final Logger log = LoggerFactory.getLogger(ReviewQueueMetrics.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ReviewQueueMetrics(JdbcTemplate jdbc, Clock clock, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.clock = clock;
        Gauge.builder("billing.payments.pending.review", this, ReviewQueueMetrics::count)
                .description("Payments held by the risk check, waiting for a manual review").register(meters);
        Gauge.builder("billing.payments.pending.review.oldest.age", this, ReviewQueueMetrics::oldestAgeSeconds)
                .baseUnit("seconds").description("How long the oldest held payment has waited").register(meters);
    }

    double count() {
        try {
            Long n = jdbc.queryForObject("SELECT count(*) FROM payment WHERE status = 'PENDING_REVIEW'", Long.class);
            return n == null ? 0 : n;
        } catch (RuntimeException e) {
            log.debug("review queue gauge not read: {}", e.toString());
            return Double.NaN;
        }
    }

    double oldestAgeSeconds() {
        try {
            Timestamp oldest = jdbc.queryForObject("SELECT min(created_at) FROM payment WHERE status = 'PENDING_REVIEW'",
                    Timestamp.class);
            return oldest == null ? 0 : Math.max(0, Duration.between(oldest.toInstant(), clock.instant()).toMillis() / 1000.0);
        } catch (RuntimeException e) {
            log.debug("review queue gauge not read: {}", e.toString());
            return Double.NaN;
        }
    }
}
