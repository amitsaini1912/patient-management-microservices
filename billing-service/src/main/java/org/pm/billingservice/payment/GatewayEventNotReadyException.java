package org.pm.billingservice.payment;

/**
 * The webhook describes a step after one we haven't recorded yet (e.g. captured while we're still CREATED, because
 * the "authorized" webhook is late). Answered with 409 and nothing stored, so the acquirer's retry is processed again.
 */
public class GatewayEventNotReadyException extends RuntimeException {

    public GatewayEventNotReadyException(String eventId, PaymentStatus status) {
        super("webhook " + eventId + " arrived before an earlier step; payment is still " + status + ", retry later");
    }
}
