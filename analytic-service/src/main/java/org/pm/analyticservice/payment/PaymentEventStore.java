package org.pm.analyticservice.payment;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Plain JDBC: the statements are Postgres upserts (ON CONFLICT) that JPA can't express. */
@Repository
public class PaymentEventStore {

    private final JdbcTemplate jdbc;

    public PaymentEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Remember the event. True = first time, apply it. False = seen before (redelivery, duplicate publish), skip.
     * One atomic statement, so two consumers handling the same event at once can't both get true.
     */
    public boolean markProcessed(UUID eventId, String topic, Instant now) {
        return jdbc.update("""
                INSERT INTO processed_event (event_id, topic, processed_at) VALUES (?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING""",
                eventId, topic, Timestamp.from(now)) == 1;
    }

    /**
     * Insert or update the payment's state - but only from an event at least as new as the one we have.
     * A late, older event (e.g. "authorized" arriving after "captured") is still recorded as processed and
     * still counts in the daily totals; it just doesn't move the status backwards.
     */
    public void upsertProjection(PaymentState state, Instant now) {
        jdbc.update("""
                INSERT INTO payment_projection (payment_id, billing_account_id, status, currency, amount_minor,
                        captured_amount_minor, refunded_amount_minor, failure_reason, last_event_type, last_event_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (payment_id) DO UPDATE SET
                    status = EXCLUDED.status,
                    captured_amount_minor = EXCLUDED.captured_amount_minor,
                    refunded_amount_minor = EXCLUDED.refunded_amount_minor,
                    failure_reason = EXCLUDED.failure_reason,
                    last_event_type = EXCLUDED.last_event_type,
                    last_event_at = EXCLUDED.last_event_at,
                    updated_at = EXCLUDED.updated_at
                WHERE payment_projection.last_event_at <= EXCLUDED.last_event_at""",
                state.paymentId(), state.billingAccountId(), state.status(), state.currency(), state.amountMinor(),
                state.capturedAmountMinor(), state.refundedAmountMinor(), state.failureReason(), state.eventType(),
                Timestamp.from(state.occurredAt()), Timestamp.from(now));
    }

    /** Add to the UTC day's totals. Not idempotent on its own - safe only behind markProcessed. */
    public void addToDailySummary(Instant occurredAt, String currency, int capturedCount, long capturedMinor,
                                  int refundCount, long refundedMinor) {
        jdbc.update("""
                INSERT INTO payment_daily_summary (summary_date, currency, captured_count, captured_minor, refund_count, refunded_minor)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (summary_date, currency) DO UPDATE SET
                    captured_count = payment_daily_summary.captured_count + EXCLUDED.captured_count,
                    captured_minor = payment_daily_summary.captured_minor + EXCLUDED.captured_minor,
                    refund_count   = payment_daily_summary.refund_count + EXCLUDED.refund_count,
                    refunded_minor = payment_daily_summary.refunded_minor + EXCLUDED.refunded_minor""",
                Date.valueOf(occurredAt.atZone(ZoneOffset.UTC).toLocalDate()), currency,
                capturedCount, capturedMinor, refundCount, refundedMinor);
    }

    public record PaymentState(UUID paymentId, UUID billingAccountId, String status, String currency, long amountMinor,
                               long capturedAmountMinor, long refundedAmountMinor, String failureReason,
                               String eventType, Instant occurredAt) {}
}
