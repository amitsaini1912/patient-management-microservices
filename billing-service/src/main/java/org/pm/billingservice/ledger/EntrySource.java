package org.pm.billingservice.ledger;

/** What business event a journal entry records; reconciliation matches entries to the acquirer's rows by it. */
public enum EntrySource {
    /** source id = payment id */
    CAPTURE,
    /** source id = refund id */
    REFUND
}
