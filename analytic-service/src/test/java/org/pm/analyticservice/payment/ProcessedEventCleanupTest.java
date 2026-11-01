package org.pm.analyticservice.payment;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.pm.analyticservice.TestcontainersConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Retention of the idempotency table, on the real Postgres (same context as KafkaConsumerIntegrationTest). */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.admin.auto-create=true",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "analytics.kafka.retry-interval=100ms"})
@Import(TestcontainersConfiguration.class)
class ProcessedEventCleanupTest {

    private static final Instant NOW = Instant.parse("2030-01-31T12:00:00Z"); // after anything other tests insert

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentEventStore store;

    @Test
    void rowsOlderThanTheRetentionGoInBatchesNewerOnesStayAndStillCatchDuplicates() {
        UUID old1 = processedAt(NOW.minus(Duration.ofDays(20)));
        UUID old2 = processedAt(NOW.minus(Duration.ofDays(15)));
        UUID old3 = processedAt(NOW.minus(Duration.ofDays(14).plusSeconds(1)));
        UUID recent = processedAt(NOW.minus(Duration.ofDays(13)));

        ProcessedEventCleanup cleanup = new ProcessedEventCleanup(jdbc, Duration.ofDays(14), 2, Clock.fixed(NOW, ZoneOffset.UTC));
        int deleted = cleanup.deleteExpired(); // batch of 2: needs two rounds for our three (+ any old rows from other tests)

        assertThat(deleted).isGreaterThanOrEqualTo(3);
        assertThat(exists(old1)).isFalse();
        assertThat(exists(old2)).isFalse();
        assertThat(exists(old3)).isFalse();
        assertThat(exists(recent)).isTrue();
        // within the retention a redelivered event is still recognised: not applied twice
        assertThat(store.markProcessed(recent, "payment.events", NOW)).isFalse();
        assertThat(cleanup.deleteExpired()).isZero(); // nothing left to do
    }

    private UUID processedAt(Instant when) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO processed_event (event_id, topic, processed_at) VALUES (?, ?, ?)", id, "payment.events",
                Timestamp.from(when));
        return id;
    }

    private boolean exists(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, id) == 1;
    }
}
