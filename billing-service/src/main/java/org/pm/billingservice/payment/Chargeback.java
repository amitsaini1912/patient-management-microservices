package org.pm.billingservice.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.pm.billingservice.money.Money;

/**
 * The card network took (part of) a captured payment back (Day 19b, ADR 0018). Written once, never changed.
 * payment.charged_back_amount_minor is the running total; these rows say when, how much and why.
 */
@Entity
@Immutable
@Table(name = "chargeback")
public class Chargeback {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private Currency currency;

    @Column(nullable = false)
    private String reason;

    @Column(name = "case_reference")
    private String caseReference;

    // market rate (payment currency -> book currency) on the day the loss is booked; 1 for same-currency payments
    @Column(name = "fx_rate", nullable = false, precision = 18, scale = 8)
    private BigDecimal fxRate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Chargeback() {}   // JPA needs a no-arg constructor

    /** With a chosen id: derived from the Idempotency-Key, so a retry finds the same row. */
    public Chargeback(UUID id, UUID paymentId, Money amount, String reason, String caseReference, BigDecimal fxRate,
                      Instant createdAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.amountMinor = amount.amountMinor();
        this.currency = amount.currency();
        this.reason = reason;
        this.caseReference = caseReference;
        this.fxRate = fxRate;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getPaymentId() { return paymentId; }
    public Money getAmount() { return new Money(amountMinor, currency); }
    public String getReason() { return reason; }
    public String getCaseReference() { return caseReference; }
    public BigDecimal getFxRate() { return fxRate; }
    public Instant getCreatedAt() { return createdAt; }
}
