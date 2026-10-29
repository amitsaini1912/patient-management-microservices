package org.pm.billingservice.ledger;

/**
 * The five classic account types. What matters in code is the "normal balance":
 * the side that makes the account grow.
 * ASSET and EXPENSE grow with a DEBIT; LIABILITY, EQUITY and REVENUE grow with a CREDIT.
 */
public enum AccountType {
    ASSET(Direction.DEBIT),
    EXPENSE(Direction.DEBIT),
    LIABILITY(Direction.CREDIT),
    EQUITY(Direction.CREDIT),
    REVENUE(Direction.CREDIT);

    private final Direction normalBalance;

    AccountType(Direction normalBalance) {
        this.normalBalance = normalBalance;
    }

    public Direction normalBalance() {
        return normalBalance;
    }
}
