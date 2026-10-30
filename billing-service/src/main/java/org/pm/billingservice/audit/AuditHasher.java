package org.pm.billingservice.audit;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * hash = HMAC-SHA256(key, prev_hash and every field of the row), hex.
 *
 * Fields are encoded as "<length>:<value>" one after the other, never just joined with a separator: with
 * "a|b" an actor "x|y" and action "z" would hash like actor "x" and action "y|z" - two different rows, one hash.
 * The time is ISO-8601 at microseconds, the precision Postgres stores, so the value read back hashes the same.
 *
 * Keyed (HMAC), not plain SHA-256: with a plain hash anyone who can write to the database can change a row and
 * recompute every hash after it. The key lives outside the database (env BILLING_AUDIT_KEY).
 */
public final class AuditHasher {

    public static final String GENESIS = "0".repeat(64);
    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public AuditHasher(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("billing.audit.hmac-key must be set (env BILLING_AUDIT_KEY, base64, >= 32 bytes)");
        }
        byte[] bytes = Base64.getDecoder().decode(base64Key.trim());
        if (bytes.length < 32) {
            throw new IllegalStateException("billing.audit.hmac-key must be at least 32 bytes (openssl rand -base64 32)");
        }
        this.key = new SecretKeySpec(bytes, ALGORITHM);
    }

    public String hash(String prevHash, long seq, Instant occurredAt, String actor, String action, String entityType,
                       String entityId, String details) {
        StringBuilder canonical = new StringBuilder();
        for (String field : new String[] {prevHash, Long.toString(seq), micros(occurredAt).toString(), actor, action,
                entityType, entityId, details}) {
            canonical.append(field.length()).append(':').append(field);
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is always available", e);
        }
    }

    public String hash(AuditEntry entry) {
        return hash(entry.prevHash(), entry.seq(), entry.occurredAt(), entry.actor(), entry.action(), entry.entityType(),
                entry.entityId(), entry.details());
    }

    public static Instant micros(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }
}
