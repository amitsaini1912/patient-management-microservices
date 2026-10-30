package org.pm.simulator.acquirer;

import java.time.Instant;

/** One authorization at the acquirer. Immutable: every change makes a new snapshot. */
public record AuthorizationState(
        String id,
        String merchantReference,
        String currency,
        long authorizedMinor,
        long capturedMinor,
        long refundedMinor,
        Status status,
        String declineReason,
        Instant createdAt) {

    public enum Status { AUTHORIZED, DECLINED, CAPTURED, VOIDED }

    AuthorizationState with(Status newStatus, long captured, long refunded) {
        return new AuthorizationState(id, merchantReference, currency, authorizedMinor, captured, refunded,
                newStatus, declineReason, createdAt);
    }
}
