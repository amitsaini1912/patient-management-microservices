package org.pm.billingservice.processor;

/**
 * The processor did not do what we asked.
 *   failed    (default): it broke or answered nonsense -> HTTP 502, the problem is upstream of us;
 *   refused   (Day 21): it refused the AMOUNT - more than is left, e.g. after a concurrent refund -> HTTP 409, the
 *                       client should re-read the payment. A refusal about the authorization's STATE ("already
 *                       voided") stays 502: then our records and the acquirer's disagree, re-reading won't help,
 *                       that is reconciliation's job.
 *   no answer (Day 23): timeout / connection refused - the OUTCOME IS UNKNOWN (the acquirer may still do it) ->
 *                       HTTP 503 + Retry-After: retry with the same Idempotency-Key, which reaches the acquirer with
 *                       the same key and gets the original result. Until Day 23 this was a 502 "refused": with the
 *                       simulator frozen for 60 s under load, 102 payments the clients were told had failed were
 *                       approved afterwards (the webhook healed them) - money held on cards, nobody capturing it.
 * Until Day 21 failed and refused were both 502: the correctness storm (100+ concurrent refunds on one payment)
 * showed the losers getting "bad gateway" for an ordinary conflict.
 */
public class PaymentProcessorException extends RuntimeException {

    private enum Kind { FAILED, REFUSED, NO_ANSWER }

    private final Kind kind;

    public PaymentProcessorException(String message) {
        this(message, null, Kind.FAILED);
    }

    public PaymentProcessorException(String message, Throwable cause) {
        this(message, cause, Kind.FAILED);
    }

    private PaymentProcessorException(String message, Throwable cause, Kind kind) {
        super(message, cause);
        this.kind = kind;
    }

    public static PaymentProcessorException refused(String message, Throwable cause) {
        return new PaymentProcessorException(message, cause, Kind.REFUSED);
    }

    public static PaymentProcessorException refused(String message) {
        return refused(message, null);
    }

    public static PaymentProcessorException noAnswer(String message, Throwable cause) {
        return new PaymentProcessorException(message, cause, Kind.NO_ANSWER);
    }

    public boolean isRefused() {
        return kind == Kind.REFUSED;
    }

    /** Timeout / unreachable: the acquirer may or may not have done it. */
    public boolean isNoAnswer() {
        return kind == Kind.NO_ANSWER;
    }
}
