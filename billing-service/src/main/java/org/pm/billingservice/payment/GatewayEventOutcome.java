package org.pm.billingservice.payment;

/** What a webhook did; stored in webhook_event.outcome and returned to the acquirer. */
public enum GatewayEventOutcome {
    /** moved the payment forward (we had missed this change) */
    APPLIED,
    /** we're already at or past this state: the normal case, and also how out-of-order events end */
    IGNORED_STALE,
    IGNORED_UNKNOWN_PAYMENT,
    /** currency or authorization id doesn't match the payment: logged, not applied */
    IGNORED_MISMATCH,
    IGNORED_UNKNOWN_TYPE,
    /** kept for the record only (refunds; see PaymentService.applyGatewayEvent) */
    RECORDED,
    /** the same event id was processed before */
    DUPLICATE
}
