package org.pm.analyticservice.payment;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes processed_event rows older than the retention (14 days by default). Until now the table grew by one row
 * per event, for ever.
 *
 * A row is only needed while the same event can still arrive again. Duplicates come from Kafka redelivery and from
 * replaying the topic (both bounded by the topic's retention, Kafka's default 7 days) and from billing's outbox
 * re-publishing an event it sent but couldn't mark (seconds later). 14 days > 7: a replay of everything Kafka still
 * has finds every event remembered. Shorter topic retention -> this can shrink; longer -> raise it.
 * Batches of {@code batchSize} rows, so one run never holds a long lock or a huge transaction.
 */
@Component
public class ProcessedEventCleanup {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventCleanup.class);

    private final JdbcTemplate jdbc;
    private final Duration retention;
    private final int batchSize;
    private final Clock clock;

    @Autowired
    public ProcessedEventCleanup(JdbcTemplate jdbc,
                                 @Value("${analytics.processed-event.retention:14d}") Duration retention,
                                 @Value("${analytics.processed-event.cleanup-batch:10000}") int batchSize) {
        this(jdbc, retention, batchSize, Clock.systemUTC());
    }

    ProcessedEventCleanup(JdbcTemplate jdbc, Duration retention, int batchSize, Clock clock) {
        this.jdbc = jdbc;
        this.retention = retention;
        this.batchSize = batchSize;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${analytics.processed-event.cleanup-delay:1h}",
               initialDelayString = "${analytics.processed-event.cleanup-delay:1h}")
    public void run() {
        try {
            deleteExpired();
        } catch (RuntimeException e) {
            log.warn("processed_event cleanup failed, next run retries: {}", e.toString());
        }
    }

    /** Returns how many rows were deleted. */
    public int deleteExpired() {
        Timestamp cutoff = Timestamp.from(clock.instant().minus(retention));
        int total = 0;
        while (true) {
            int deleted = jdbc.update("""
                    DELETE FROM processed_event WHERE event_id IN (
                        SELECT event_id FROM processed_event WHERE processed_at < ? LIMIT ?)""", cutoff, batchSize);
            total += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info("processed_event cleanup: deleted {} row(s) processed before {}", total, cutoff.toInstant());
        }
        return total;
    }
}
