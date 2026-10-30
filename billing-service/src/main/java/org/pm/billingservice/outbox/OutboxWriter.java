package org.pm.billingservice.outbox;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes an event to the outbox table. MANDATORY: it refuses to run without a transaction, because an
 * outbox row written outside the business transaction is just a dual write again.
 * JdbcTemplate joins the surrounding JPA transaction (same DataSource, same connection).
 */
@Component
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public OutboxWriter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(UUID eventId, String aggregateType, UUID aggregateId, String eventType,
                       String topic, String messageKey, byte[] payload) {
        jdbc.update("""
                INSERT INTO outbox_event (id, aggregate_type, aggregate_id, event_type, topic, message_key, payload, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                eventId, aggregateType, aggregateId, eventType, topic, messageKey, payload,
                Timestamp.from(clock.instant()));
    }
}
