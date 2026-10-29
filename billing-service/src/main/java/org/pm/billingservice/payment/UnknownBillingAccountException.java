package org.pm.billingservice.payment;

import java.util.UUID;

/** The request body points at a billing account that doesn't exist. Maps to HTTP 422 (the URL itself was found). */
public class UnknownBillingAccountException extends RuntimeException {

    public UnknownBillingAccountException(UUID billingAccountId) {
        super("billing account " + billingAccountId + " not found");
    }
}
