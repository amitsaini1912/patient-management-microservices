package org.pm.billingservice.ledger;

/** A journal entry broke a double-entry rule, so it was not posted. */
public class UnbalancedEntryException extends RuntimeException {

    public UnbalancedEntryException(String message) {
        super(message);
    }
}
