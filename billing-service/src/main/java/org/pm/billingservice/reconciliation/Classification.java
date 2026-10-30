package org.pm.billingservice.reconciliation;

/** What's wrong with one transaction when our ledger and the acquirer's report are compared. */
public enum Classification {
    /** the acquirer booked it, our ledger doesn't have it (e.g. we lost a refund, or a capture we never recorded) */
    MISSING_IN_LEDGER,
    /** our ledger has it, the acquirer's report doesn't (e.g. the acquirer lost it, or our capture never reached it) */
    MISSING_IN_SETTLEMENT,
    AMOUNT_MISMATCH,
    CURRENCY_MISMATCH
}
