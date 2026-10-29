package org.pm.billingservice.payment;

import java.util.UUID;

/** Maps to HTTP 404. */
public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(UUID paymentId) {
        super("payment " + paymentId + " not found");
    }
}
