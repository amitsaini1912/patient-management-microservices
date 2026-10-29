package org.pm.billingservice.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

@Entity
@Table(name = "ledger_account")
public class LedgerAccount {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;

    @Column(nullable = false, length = 3)
    private Currency currency;

    // null for system accounts
    @Column(name = "billing_account_id")
    private UUID billingAccountId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerAccount() {}   // JPA needs a no-arg constructor

    private LedgerAccount(AccountPurpose purpose, Currency currency, UUID billingAccountId) {
        this.id = UUID.randomUUID();
        this.purpose = purpose;
        this.type = purpose.type();
        this.currency = currency;
        this.billingAccountId = billingAccountId;
        this.createdAt = Instant.now();
    }

    public static LedgerAccount patientReceivable(UUID billingAccountId, Currency currency) {
        return new LedgerAccount(AccountPurpose.PATIENT_RECEIVABLE, currency, billingAccountId);
    }

    public UUID getId() { return id; }
    public AccountPurpose getPurpose() { return purpose; }
    public AccountType getType() { return type; }
    public Currency getCurrency() { return currency; }
    public UUID getBillingAccountId() { return billingAccountId; }
    public Instant getCreatedAt() { return createdAt; }
}
