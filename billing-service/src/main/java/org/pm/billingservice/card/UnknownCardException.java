package org.pm.billingservice.card;

/** The payment's card token isn't one the vault issued, or the card has expired. 422, nothing charged. */
public class UnknownCardException extends RuntimeException {

    public UnknownCardException(String message) {
        super(message);
    }
}
