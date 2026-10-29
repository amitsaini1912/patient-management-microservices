package org.pm.billingservice.money;

import java.util.Currency;

/** Adding INR to USD is a bug, not a calculation - it must fail loudly. */
public class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(Currency left, Currency right) {
        super("currency mismatch: " + left + " vs " + right);
    }
}
