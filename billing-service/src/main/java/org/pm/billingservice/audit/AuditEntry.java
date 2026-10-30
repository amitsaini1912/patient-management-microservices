package org.pm.billingservice.audit;

import java.time.Instant;

/** One row of audit_log, exactly as stored. {@code occurredAt} has microsecond precision (what Postgres keeps). */
public record AuditEntry(long seq, Instant occurredAt, String actor, String action, String entityType,
                         String entityId, String details, String prevHash, String hash) {
}
