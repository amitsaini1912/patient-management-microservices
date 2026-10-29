package org.pm.billingservice.payment;

import java.util.UUID;

/** A status move that the state machine doesn't allow, e.g. refunding a payment that was never captured. Maps to HTTP 409. */
public class IllegalPaymentStateException extends RuntimeException {

    private final PaymentStatus from;
    private final PaymentStatus to;

    public IllegalPaymentStateException(UUID paymentId, PaymentStatus from, PaymentStatus to) {
        super("payment " + paymentId + " cannot move from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public PaymentStatus getFrom() { return from; }
    public PaymentStatus getTo() { return to; }
}
