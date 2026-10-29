package org.pm.billingservice.ledger;

/**
 * What a ledger account is for. Each purpose has a fixed account type.
 * PATIENT_RECEIVABLE exists once per billing account; the others are system
 * accounts that exist once per currency.
 */
public enum AccountPurpose {
    /** What a patient owes the hospital. */
    PATIENT_RECEIVABLE(AccountType.ASSET),
    /** What an insurer owes the hospital for a claim. */
    INSURER_RECEIVABLE(AccountType.ASSET),
    /** Card money the acquirer collected for us but hasn't paid out yet. Cleared at settlement. */
    PROCESSOR_RECEIVABLE(AccountType.ASSET),
    /** Money the hospital has earned. */
    HOSPITAL_REVENUE(AccountType.REVENUE),
    /** Money given back; kept apart from revenue so refunds stay visible (a "contra-revenue" account). */
    REFUNDS(AccountType.EXPENSE),
    /** Gains (credit) and losses (debit) from currency conversion. */
    FX_GAIN_LOSS(AccountType.REVENUE),
    /** Money we received but can't match to anything yet. Should be zero after reconciliation. */
    SUSPENSE(AccountType.LIABILITY);

    private final AccountType type;

    AccountPurpose(AccountType type) {
        this.type = type;
    }

    public AccountType type() {
        return type;
    }
}
