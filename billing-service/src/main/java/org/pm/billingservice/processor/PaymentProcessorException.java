package org.pm.billingservice.processor;

/**
 * The processor did not do what we asked.
 *   failed  (default): it broke, didn't answer, or answered nonsense -> HTTP 502, the problem is upstream of us;
 *   refused (Day 21): it refused the AMOUNT - more than is left, e.g. after a concurrent refund -> HTTP 409, the
 *                     client should re-read the payment. A refusal about the authorization's STATE ("already
 *                     voided") stays 502: then our records and the acquirer's disagree, re-reading won't help,
 *                     that is reconciliation's job.
 * Until Day 21 both were 502: the correctness storm (100+ concurrent refunds on one payment) showed the losers
 * getting "bad gateway" for an ordinary conflict.
 */
public class PaymentProcessorException extends RuntimeException {

    private final boolean refused;

    public PaymentProcessorException(String message) {
        this(message, null, false);
    }

    public PaymentProcessorException(String message, Throwable cause) {
        this(message, cause, false);
    }

    private PaymentProcessorException(String message, Throwable cause, boolean refused) {
        super(message, cause);
        this.refused = refused;
    }

    public static PaymentProcessorException refused(String message, Throwable cause) {
        return new PaymentProcessorException(message, cause, true);
    }

    public static PaymentProcessorException refused(String message) {
        return refused(message, null);
    }

    public boolean isRefused() {
        return refused;
    }
}
