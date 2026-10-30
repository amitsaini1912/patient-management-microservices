package org.pm.billingservice.outbox;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RetriableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes outbox rows to Kafka, oldest first, and marks them SENT.
 *
 * Order: every event of a payment has the same key, so the same partition; we send one row at a time in
 * {@code position} order and stop at the first temporary failure, so a later event never overtakes an earlier one.
 * Only one instance relays at a time (Postgres advisory lock) - with several relays running side by side
 * (plain SKIP LOCKED), instance B could publish "captured" before instance A published "authorized".
 *
 * Delivery is AT LEAST ONCE: if we crash after Kafka acked but before our commit, the row is sent again.
 * Consumers dedup on event_id.
 *
 * Kafka down: the send times out, the row stays PENDING with attempts+1, and the relay backs off
 * (1s, 2s, 4s ... 60s). Payments keep working - they only write the outbox table.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    /** Any constant; it just has to be the same on every instance. */
    private static final long RELAY_LOCK_ID = 0x6f7574626f78L; // "outbox"

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final KafkaTemplate<String, byte[]> kafka;
    private final OutboxProperties properties;
    private final Clock clock;

    // per instance; only touched by the scheduler thread (and tests)
    private volatile int consecutiveFailures;
    private volatile Instant pausedUntil = Instant.MIN;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate transactionTemplate, KafkaTemplate<String, byte[]> kafka,
                       OutboxProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.kafka = kafka;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${billing.outbox.poll-interval:PT0.5S}")
    public void poll() {
        if (clock.instant().isBefore(pausedUntil)) {
            return; // backing off after a failure
        }
        int sent;
        do {
            sent = relayBatch();
        } while (sent == properties.batchSize()); // a full batch: there may be more waiting
    }

    /** One transaction: claim up to batchSize pending rows, publish them in order. Returns how many were sent. */
    public int relayBatch() {
        Integer sent = transactionTemplate.execute(tx -> {
            // xact lock: released automatically at commit/rollback, also if this instance dies
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, RELAY_LOCK_ID))) {
                return 0; // another instance is relaying right now
            }
            List<Row> batch = jdbc.query("""
                            SELECT id, event_type, topic, message_key, payload FROM outbox_event
                            WHERE status = 'PENDING'
                            ORDER BY position
                            LIMIT ?
                            FOR UPDATE SKIP LOCKED""",
                    (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("event_type"), rs.getString("topic"),
                            rs.getString("message_key"), rs.getBytes("payload")),
                    properties.batchSize());

            int count = 0;
            for (Row row : batch) {
                try {
                    send(row);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt(); // shutting down; the row stays PENDING
                    }
                    Throwable cause = rootCause(e);
                    if (isRetriable(e)) {
                        markAttemptFailed(row, cause, false);
                        backOff(row, cause);
                        break; // keep order: nothing after this row may go out before it
                    }
                    // Kafka will never take this record (e.g. too large). Park it so it doesn't block everything.
                    log.error("outbox event {} ({}) rejected permanently, marked FAILED", row.id(), row.eventType(), cause);
                    markAttemptFailed(row, cause, true);
                    continue;
                }
                jdbc.update("UPDATE outbox_event SET status = 'SENT', sent_at = ?, attempts = attempts + 1 WHERE id = ?",
                        Timestamp.from(clock.instant()), row.id());
                count++;
            }
            return count;
        });
        if (sent != null && sent > 0) {
            consecutiveFailures = 0;
        }
        return sent == null ? 0 : sent;
    }

    @Scheduled(fixedDelayString = "${billing.outbox.cleanup-fixed-delay:PT1H}",
               initialDelayString = "${billing.outbox.cleanup-fixed-delay:PT1H}")
    public void deleteOldSentEvents() {
        int deleted = jdbc.update("DELETE FROM outbox_event WHERE status = 'SENT' AND sent_at < ?",
                Timestamp.from(clock.instant().minus(properties.retention())));
        if (deleted > 0) {
            log.info("deleted {} sent outbox event(s) older than {}", deleted, properties.retention());
        }
    }

    /** Blocks until Kafka acks (acks=all). Bounded by the producer's delivery.timeout.ms. */
    private void send(Row row) throws ExecutionException, InterruptedException {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(row.topic(), row.messageKey(), row.payload());
        // also as headers, so a consumer can dedup / route without parsing the payload
        record.headers().add("event_id", row.id().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("event_type", row.eventType().getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get();
    }

    private void markAttemptFailed(Row row, Throwable cause, boolean permanently) {
        jdbc.update("UPDATE outbox_event SET attempts = attempts + 1, last_error = ?, status = ? WHERE id = ?",
                truncate(cause.getClass().getSimpleName() + ": " + cause.getMessage()),
                permanently ? "FAILED" : "PENDING", row.id());
    }

    private void backOff(Row row, Throwable cause) {
        int failures = ++consecutiveFailures;
        long factor = 1L << Math.min(failures - 1, 20);
        Duration pause = properties.initialBackoff().multipliedBy(factor);
        if (pause.compareTo(properties.maxBackoff()) > 0) {
            pause = properties.maxBackoff();
        }
        pausedUntil = clock.instant().plus(pause);
        log.warn("could not publish outbox event {} ({} failure(s) in a row), pausing relay for {}: {}",
                row.id(), failures, pause, cause.toString());
    }

    /** Timeouts, broker not available, leader moving ... -> try again later. Anything else won't get better. */
    private static boolean isRetriable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof RetriableException || t instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }

    private static Throwable rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 1000 ? s : s.substring(0, 1000);
    }

    /** For tests and the actuator someday: the time the relay will try again after a failure. */
    public Instant pausedUntil() {
        return pausedUntil;
    }

    private record Row(UUID id, String eventType, String topic, String messageKey, byte[] payload) {}
}
