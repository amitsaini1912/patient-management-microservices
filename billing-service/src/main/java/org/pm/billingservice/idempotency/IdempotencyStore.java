package org.pm.billingservice.idempotency;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Plain JDBC on purpose: every statement here is its own auto-committed transaction, so other
 * requests see the claim immediately, and nothing waits on the (long) business request in between.
 * Each write is one atomic statement - no "read, decide, write" races.
 *
 * {@code locked_at} doubles as a fencing token: claim / take-over set it, and complete / release only
 * match the row while it still holds OUR value. An owner that was so slow that a retry took the key
 * over can therefore not overwrite or delete the new owner's row.
 */
@Repository
public class IdempotencyStore {

    private final JdbcTemplate jdbc;

    public IdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claim the key. True = we own it (new row, or an expired one reused). False = someone already has it.
     * INSERT ... ON CONFLICT makes "check if it exists, else insert" ONE atomic step: two concurrent
     * requests can't both see "not there" and both insert. The DO UPDATE only fires for an expired row
     * the cleanup job hasn't deleted yet; a live row is left alone (0 rows -> false).
     */
    public boolean tryClaim(String scope, String key, String requestHash, Instant now, Instant expiresAt) {
        return jdbc.update("""
                INSERT INTO idempotency_key (scope, idempotency_key, request_hash, status, locked_at, expires_at)
                VALUES (?, ?, ?, 'IN_PROGRESS', ?, ?)
                ON CONFLICT (scope, idempotency_key) DO UPDATE
                SET request_hash = EXCLUDED.request_hash, status = 'IN_PROGRESS', response_status = NULL,
                    response_content_type = NULL, response_location = NULL, response_body = NULL,
                    locked_at = EXCLUDED.locked_at, created_at = EXCLUDED.locked_at, expires_at = EXCLUDED.expires_at
                WHERE idempotency_key.expires_at <= EXCLUDED.locked_at""",
                scope, key, requestHash, Timestamp.from(now), Timestamp.from(expiresAt)) == 1;
    }

    /**
     * Take over an abandoned IN_PROGRESS key (its owner crashed). The WHERE re-checks everything, so
     * of several retries racing for the same stale row, exactly one UPDATE matches.
     */
    public boolean tryTakeOver(String scope, String key, String requestHash, Instant staleBefore, Instant now) {
        return jdbc.update("""
                UPDATE idempotency_key SET locked_at = ?
                WHERE scope = ? AND idempotency_key = ? AND request_hash = ?
                  AND status = 'IN_PROGRESS' AND locked_at < ?""",
                Timestamp.from(now), scope, key, requestHash, Timestamp.from(staleBefore)) == 1;
    }

    public Optional<IdempotencyRecord> find(String scope, String key) {
        return jdbc.query("""
                        SELECT scope, idempotency_key, request_hash, status, response_status, response_content_type,
                               response_location, response_body, locked_at
                        FROM idempotency_key WHERE scope = ? AND idempotency_key = ?""",
                (rs, row) -> new IdempotencyRecord(
                        rs.getString("scope"),
                        rs.getString("idempotency_key"),
                        rs.getString("request_hash"),
                        IdempotencyRecord.Status.valueOf(rs.getString("status")),
                        (Integer) rs.getObject("response_status"),
                        rs.getString("response_content_type"),
                        rs.getString("response_location"),
                        rs.getBytes("response_body"),
                        rs.getTimestamp("locked_at").toInstant()),
                scope, key).stream().findFirst();
    }

    /** Store the response. False = we no longer own the key (taken over), nothing was written. */
    public boolean complete(String scope, String key, Instant lockedAt, int status, String contentType,
                            String location, byte[] body) {
        return jdbc.update("""
                UPDATE idempotency_key
                SET status = 'COMPLETED', response_status = ?, response_content_type = ?, response_location = ?,
                    response_body = ?
                WHERE scope = ? AND idempotency_key = ? AND status = 'IN_PROGRESS' AND locked_at = ?""",
                status, contentType, location, body, scope, key, Timestamp.from(lockedAt)) == 1;
    }

    /** Give the key back (5xx or a retryable error): the client may retry with the same key. */
    public void release(String scope, String key, Instant lockedAt) {
        jdbc.update("""
                DELETE FROM idempotency_key
                WHERE scope = ? AND idempotency_key = ? AND status = 'IN_PROGRESS' AND locked_at = ?""",
                scope, key, Timestamp.from(lockedAt));
    }

    public int deleteExpired(Instant now) {
        return jdbc.update("DELETE FROM idempotency_key WHERE expires_at < ?", Timestamp.from(now));
    }
}
