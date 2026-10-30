package org.pm.billingservice.idempotency;

import java.time.Instant;

/** One row of idempotency_key. {@code responseStatus} etc. are null while IN_PROGRESS. */
public record IdempotencyRecord(
        String scope,
        String key,
        String requestHash,
        Status status,
        Integer responseStatus,
        String responseContentType,
        String responseLocation,
        byte[] responseBody,
        Instant lockedAt) {

    public enum Status { IN_PROGRESS, COMPLETED }
}
