package org.pm.billingservice.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends to the hash-chained audit_log (ADR 0016), always inside the transaction that makes the change:
 * no change without its audit row, no audit row for a change that rolled back (and no gap in seq either).
 *
 * Appends are serialized by locking the single audit_chain_head row (FOR UPDATE) until commit, so the chain can't
 * fork. Lock order matters: we FLUSH the transaction's pending JPA changes first, so every transaction locks its
 * business rows (payment ...) before the chain head. Without it, "lock head, then flush payment" (authorize/void
 * write at commit) against "flush payment, then lock head" (capture/refund flush early) on one payment deadlocks -
 * AuditIntegrationTest reproduces it. Today's API paths rarely get there (the processor refuses one of a racing
 * capture/void before either reaches the DB), so this is about keeping one lock order, not a hot path.
 */
@Component
public class AuditLog {

    private static final RowMapper<AuditEntry> ROW = (rs, i) -> new AuditEntry(rs.getLong("seq"),
            rs.getTimestamp("occurred_at").toInstant(), rs.getString("actor"), rs.getString("action"),
            rs.getString("entity_type"), rs.getString("entity_id"), rs.getString("details"),
            rs.getString("prev_hash"), rs.getString("hash"));

    private final JdbcTemplate jdbc;
    private final AuditHasher hasher;
    private final Clock clock;
    private final ObjectMapper canonicalJson;

    @PersistenceContext
    private EntityManager entityManager;

    public AuditLog(JdbcTemplate jdbc, Clock clock, ObjectMapper objectMapper,
                    @Value("${billing.audit.hmac-key:}") String hmacKey) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.hasher = new AuditHasher(hmacKey);
        // sorted keys and no pretty-printing: the same details always produce the same text
        this.canonicalJson = objectMapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(SerializationFeature.INDENT_OUTPUT, false);
    }

    /** {@code details}: what changed, as simple values. Never a card number or a secret. Null values are dropped. */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuditEntry append(String action, String entityType, String entityId, Map<String, ?> details) {
        entityManager.flush(); // business rows first, chain head last (see class comment)

        Map<String, Object> head = jdbc.queryForMap("SELECT last_seq, last_hash FROM audit_chain_head WHERE id = 1 FOR UPDATE");
        long seq = ((Number) head.get("last_seq")).longValue() + 1;
        String prevHash = (String) head.get("last_hash");
        Instant occurredAt = AuditHasher.micros(clock.instant());
        String actor = AuditActor.current();
        String json = canonical(details);
        String hash = hasher.hash(prevHash, seq, occurredAt, actor, action, entityType, entityId, json);

        jdbc.update("""
                        INSERT INTO audit_log (seq, occurred_at, actor, action, entity_type, entity_id, details, prev_hash, hash)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                seq, Timestamp.from(occurredAt), actor, action, entityType, entityId, json, prevHash, hash);
        jdbc.update("UPDATE audit_chain_head SET last_seq = ?, last_hash = ? WHERE id = 1", seq, hash);
        return new AuditEntry(seq, occurredAt, actor, action, entityType, entityId, json, prevHash, hash);
    }

    public List<AuditEntry> forEntity(String entityType, String entityId, int limit) {
        return jdbc.query("SELECT * FROM audit_log WHERE entity_type = ? AND entity_id = ? ORDER BY seq LIMIT ?",
                ROW, entityType, entityId, limit);
    }

    /** Rows with seq in (afterSeq, upToSeq], in order: one page of the chain for the verifier. */
    List<AuditEntry> page(long afterSeq, long upToSeq, int size) {
        return jdbc.query("SELECT * FROM audit_log WHERE seq > ? AND seq <= ? ORDER BY seq LIMIT ?",
                ROW, afterSeq, upToSeq, size);
    }

    record Head(long lastSeq, String lastHash) {}

    Head head() {
        return jdbc.queryForObject("SELECT last_seq, last_hash FROM audit_chain_head WHERE id = 1",
                (rs, i) -> new Head(rs.getLong("last_seq"), rs.getString("last_hash")));
    }

    AuditHasher hasher() {
        return hasher;
    }

    private String canonical(Map<String, ?> details) {
        Map<String, Object> sorted = new TreeMap<>();
        if (details != null) {
            details.forEach((k, v) -> {
                if (v != null) {
                    sorted.put(k, v);
                }
            });
        }
        try {
            return canonicalJson.writeValueAsString(sorted);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("audit details must be plain values", e);
        }
    }
}
