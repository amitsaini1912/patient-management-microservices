package org.pm.simulator.acquirer;

import java.time.Instant;

/**
 * One authorization at the acquirer. Immutable: every change makes a new snapshot.
 * Day 11: what the card network answered (field 39, STAN/RRN, approval code). Never the PAN.
 */
public record AuthorizationState(
        String id,
        String merchantReference,
        String currency,
        long authorizedMinor,
        long capturedMinor,
        long refundedMinor,
        Status status,
        String declineReason,
        String networkResponseCode,
        boolean retryable,
        String stan,
        String rrn,
        String approvalCode,
        Instant createdAt) {

    public enum Status { AUTHORIZED, DECLINED, CAPTURED, VOIDED }

    AuthorizationState with(Status newStatus, long captured, long refunded) {
        return new AuthorizationState(id, merchantReference, currency, authorizedMinor, captured, refunded,
                newStatus, declineReason, networkResponseCode, retryable, stan, rrn, approvalCode, createdAt);
    }
}
