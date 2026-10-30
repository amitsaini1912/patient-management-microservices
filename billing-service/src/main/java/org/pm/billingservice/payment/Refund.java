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

/** One successful refund of (part of) a captured payment. Written once, never changed. */
@Entity
@Immutable
@Table(name = "refund")
public class Refund {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private Currency currency;

    @Column(name = "processor_reference", nullable = false)
    private String processorReference;

    private String reason;

    // market rate (refund currency -> book currency) on the day of the refund; 1 for same-currency payments
    @Column(name = "fx_rate", nullable = false, precision = 18, scale = 8)
    private BigDecimal fxRate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Refund() {}   // JPA needs a no-arg constructor

    public Refund(UUID paymentId, Money amount, String processorReference, String reason) {
        this(UUID.randomUUID(), paymentId, amount, processorReference, reason, BigDecimal.ONE);
    }

    /** With a chosen id: PaymentService derives it from the Idempotency-Key, so a retry finds the same row. */
    public Refund(UUID id, UUID paymentId, Money amount, String processorReference, String reason, BigDecimal fxRate) {
        this.fxRate = fxRate;
        this.id = id;
        this.paymentId = paymentId;
        this.amountMinor = amount.amountMinor();
        this.currency = amount.currency();
        this.processorReference = processorReference;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getPaymentId() { return paymentId; }
    public Money getAmount() { return new Money(amountMinor, currency); }
    public String getProcessorReference() { return processorReference; }
    public String getReason() { return reason; }
    public BigDecimal getFxRate() { return fxRate; }
    public Instant getCreatedAt() { return createdAt; }
}
