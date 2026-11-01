package org.pm.patientservice.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes outbox rows to Kafka topic {@code patient} (ADR 0030), oldest first, key = patient id.
 *
 * Each batch is claimed with FOR UPDATE SKIP LOCKED, so several patient-service instances share the work instead of
 * sending a row twice. A row is marked published only after Kafka acknowledged it (send().get()). Kafka down: the
 * row keeps its place, attempts + last_error are recorded, and the batch stops (no point trying the rest now);
 * the next run tries again - the patient was saved either way. At-least-once: a crash after Kafka's ack but before
 * the commit sends the row again; analytics upserts patients by id, so a repeat is harmless.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    record Row(UUID id, UUID patientId, byte[] payload) {}

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, byte[]> kafka;
    private final TransactionTemplate transactions;
    private final String topic;
    private final int batchSize;
    private final Duration sendTimeout;
    private final Duration keepPublished;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, byte[]> kafka, TransactionTemplate transactions,
                       MeterRegistry meters,
                       @Value("${patient.outbox.topic:patient}") String topic,
                       @Value("${patient.outbox.batch-size:100}") int batchSize,
                       @Value("${patient.outbox.send-timeout:5s}") Duration sendTimeout,
                       @Value("${patient.outbox.keep-published:7d}") Duration keepPublished) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.transactions = transactions;
        this.topic = topic;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
        this.keepPublished = keepPublished;
        Gauge.builder("patient.outbox.pending", this, OutboxRelay::pending)
                .description("Patient events written but not yet on Kafka").register(meters);
    }

    @Scheduled(fixedDelayString = "${patient.outbox.relay-delay:500ms}")
    public void run() {
        try {
            publishPending();
        } catch (RuntimeException e) {
            log.warn("outbox relay run failed (database?), next run retries: {}", e.toString());
        }
    }

    /** Returns how many rows this run published. */
    public int publishPending() {
        Integer published = transactions.execute(tx -> {
            List<Row> batch = jdbc.query("""
                    SELECT id, patient_id, payload FROM patient_event_outbox
                    WHERE published_at IS NULL ORDER BY created_at, id LIMIT ? FOR UPDATE SKIP LOCKED""",
                    (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
                            rs.getBytes("payload")), batchSize);
            int sent = 0;
            for (Row row : batch) {
                try {
                    kafka.send(topic, row.patientId().toString(), row.payload())
                            .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    String error = e instanceof InterruptedException ? "interrupted" : String.valueOf(e.getCause() != null ? e.getCause() : e);
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    jdbc.update("UPDATE patient_event_outbox SET attempts = attempts + 1, last_error = ? WHERE id = ?",
                            error.length() > 500 ? error.substring(0, 500) : error, row.id());
                    log.warn("outbox event {} not published yet ({}), retried next run", row.id(), error);
                    break; // keep the order: the rest waits for this one
                }
                jdbc.update("UPDATE patient_event_outbox SET published_at = now(), attempts = attempts + 1 WHERE id = ?",
                        row.id());
                sent++;
            }
            return sent;
        });
        return published == null ? 0 : published;
    }

    /** Published rows are only history: kept {@code keepPublished} (for "did we send it?"), then deleted. */
    @Scheduled(fixedDelayString = "${patient.outbox.cleanup-delay:1h}", initialDelayString = "${patient.outbox.cleanup-delay:1h}")
    public void deleteOldPublished() {
        try {
            int deleted = jdbc.update("DELETE FROM patient_event_outbox WHERE published_at < now() - make_interval(secs => ?)",
                    (double) keepPublished.toSeconds());
            if (deleted > 0) {
                log.info("outbox cleanup: deleted {} published row(s) older than {}", deleted, keepPublished);
            }
        } catch (RuntimeException e) {
            log.warn("outbox cleanup failed, next run retries: {}", e.toString());
        }
    }

    private double pending() {
        try {
            Long n = jdbc.queryForObject("SELECT count(*) FROM patient_event_outbox WHERE published_at IS NULL", Long.class);
            return n == null ? 0 : n;
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}
