package org.pm.billingservice.processor;

/** The processor refused or failed a capture/void. Maps to HTTP 502: the problem is upstream of us. */
public class PaymentProcessorException extends RuntimeException {

    public PaymentProcessorException(String message) {
        super(message);
    }

    public PaymentProcessorException(String message, Throwable cause) {
        super(message, cause);
    }
}
