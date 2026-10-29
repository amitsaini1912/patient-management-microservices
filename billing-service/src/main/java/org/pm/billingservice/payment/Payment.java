package org.pm.billingservice.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import org.pm.billingservice.money.CurrencyMismatchException;
import org.pm.billingservice.money.Money;

/**
 * One card payment. Status changes only through the methods below, and each one asks
 * {@link PaymentStateMachine} first, so an illegal move can't happen through this class.
 *
 * Concurrency: @Version. Two requests that load the same payment and both change it can't
 * both win; the second commit fails instead of silently overwriting the first.
 */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    private UUID id;

    @Column(name = "billing_account_id", nullable = false)
    private UUID billingAccountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private Currency currency;

    @Column(name = "captured_amount_minor", nullable = false)
    private long capturedAmountMinor;

    @Column(name = "refunded_amount_minor", nullable = false)
    private long refundedAmountMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    // the processor's id for the authorization; null until authorized
    @Column(name = "processor_reference")
    private String processorReference;

    // Long, not long: Spring Data treats "version == null" as a new entity, so save() persists
    // instead of merging even though we assign the UUID ourselves.
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {}   // JPA needs a no-arg constructor

    private Payment(UUID billingAccountId, Money amount) {
        Objects.requireNonNull(billingAccountId, "billingAccountId is required");
        Objects.requireNonNull(amount, "amount is required");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("payment amount must be positive, was " + amount);
        }
        this.id = UUID.randomUUID();
        this.billingAccountId = billingAccountId;
        this.amountMinor = amount.amountMinor();
        this.currency = amount.currency();
        this.status = PaymentStatus.CREATED;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public static Payment create(UUID billingAccountId, Money amount) {
        return new Payment(billingAccountId, amount);
    }

    public void holdForReview() {
        moveTo(PaymentStatus.PENDING_REVIEW);
    }

    public void authorize(String processorReference) {
        if (processorReference == null || processorReference.isBlank()) {
            throw new IllegalArgumentException("processor reference is required");
        }
        moveTo(PaymentStatus.AUTHORIZED);
        this.processorReference = processorReference;
    }

    public void fail(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("failure reason is required");
        }
        moveTo(PaymentStatus.FAILED);
        this.failureReason = reason;
    }

    /** Take all or part of the authorized amount. One capture only; the rest of the hold is released. */
    public void capture(Money captureAmount) {
        checkCapture(captureAmount);
        this.capturedAmountMinor = captureAmount.amountMinor();
        moveTo(PaymentStatus.CAPTURED);
    }

    /** Throws exactly what capture() would, without changing anything. Lets callers fail before calling the processor. */
    public void checkCapture(Money captureAmount) {
        requirePositiveInOurCurrency(captureAmount);
        PaymentStateMachine.requireMove(id, status, PaymentStatus.CAPTURED);
        if (captureAmount.amountMinor() > amountMinor) {
            throw new IllegalArgumentException("cannot capture " + captureAmount + ", only " + getAmount() + " is authorized");
        }
    }

    public void voidAuthorization() {
        moveTo(PaymentStatus.VOIDED);
    }

    /** Throws exactly what voidAuthorization() would, without changing anything. */
    public void checkVoid() {
        PaymentStateMachine.requireMove(id, status, PaymentStatus.VOIDED);
    }

    public void expire() {
        moveTo(PaymentStatus.EXPIRED);
    }

    /** Give back some or all of the captured money. Several partial refunds are allowed. */
    public void refund(Money refundAmount) {
        requirePositiveInOurCurrency(refundAmount);
        long refundedAfter = Math.addExact(refundedAmountMinor, refundAmount.amountMinor());
        PaymentStatus next = refundedAfter == capturedAmountMinor
                ? PaymentStatus.REFUNDED
                : PaymentStatus.PARTIALLY_REFUNDED;
        // state first: refunding a payment that was never captured is a 409, not an amount error
        PaymentStateMachine.requireMove(id, status, next);
        if (refundedAfter > capturedAmountMinor) {
            throw new IllegalArgumentException("cannot refund " + refundAmount + ", only "
                    + getRefundableAmount() + " is left to refund");
        }
        this.refundedAmountMinor = refundedAfter;
        moveTo(next);
    }

    private void moveTo(PaymentStatus next) {
        PaymentStateMachine.requireMove(id, status, next);
        this.status = next;
    }

    private void requirePositiveInOurCurrency(Money amount) {
        Objects.requireNonNull(amount, "amount is required");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("amount must be positive, was " + amount);
        }
        if (!amount.currency().equals(currency)) {
            throw new CurrencyMismatchException(currency, amount.currency());
        }
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getBillingAccountId() { return billingAccountId; }
    public Money getAmount() { return new Money(amountMinor, currency); }
    public Money getCapturedAmount() { return new Money(capturedAmountMinor, currency); }
    public Money getRefundedAmount() { return new Money(refundedAmountMinor, currency); }
    public Money getRefundableAmount() { return new Money(capturedAmountMinor - refundedAmountMinor, currency); }
    public PaymentStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public String getProcessorReference() { return processorReference; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
