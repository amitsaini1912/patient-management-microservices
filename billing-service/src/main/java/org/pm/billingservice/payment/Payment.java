package org.pm.billingservice.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import org.pm.billingservice.card.CardInfo;
import org.pm.billingservice.fx.FxRate;
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

    // The currency our books are in (the billing account's, INR) and the rate LOCKED at authorization:
    // 1 unit of `currency` = fxRate units of bookCurrency. Capture and the patient side of refunds use it (ADR 0012).
    @Column(name = "book_currency", nullable = false, length = 3)
    private Currency bookCurrency;

    @Column(name = "fx_rate", nullable = false, precision = 18, scale = 8)
    private BigDecimal fxRate;

    @Column(name = "fx_rate_id")
    private UUID fxRateId;

    // the card, as billing may know it: a vault token + metadata, never the number (ADR 0013)
    @Column(name = "card_token")
    private String cardToken;

    @Column(name = "card_last4")
    private String cardLast4;

    @Column(name = "card_brand")
    private String cardBrand;

    @Column(name = "card_fingerprint")
    private String cardFingerprint;

    @Column(name = "captured_amount_minor", nullable = false)
    private long capturedAmountMinor;

    @Column(name = "refunded_amount_minor", nullable = false)
    private long refundedAmountMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    // what the card network answered (ISO 8583 field 39) and whether a decline may be retried (ADR 0014)
    @Column(name = "network_response_code")
    private String networkResponseCode;

    @Column(nullable = false)
    private boolean retryable;

    // the processor's id for the authorization; null until authorized
    @Column(name = "processor_reference")
    private String processorReference;

    // when the hold was placed; the expiry job counts from here
    @Column(name = "authorized_at")
    private Instant authorizedAt;

    // Long, not long: Spring Data treats "version == null" as a new entity, so save() persists
    // instead of merging even though we assign the UUID ourselves.
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {}   // JPA needs a no-arg constructor

    private Payment(UUID id, UUID billingAccountId, Money amount, FxRate lockedRate) {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(billingAccountId, "billingAccountId is required");
        Objects.requireNonNull(amount, "amount is required");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("payment amount must be positive, was " + amount);
        }
        this.id = id;
        this.billingAccountId = billingAccountId;
        this.amountMinor = amount.amountMinor();
        this.currency = amount.currency();
        Objects.requireNonNull(lockedRate, "lockedRate is required");
        if (!lockedRate.base().equals(amount.currency())) {
            throw new IllegalArgumentException("locked rate is for " + lockedRate.base() + ", payment is in " + amount.currency());
        }
        this.bookCurrency = lockedRate.quote();
        this.fxRate = lockedRate.rate();
        this.fxRateId = lockedRate.id();
        this.status = PaymentStatus.CREATED;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public static Payment create(UUID billingAccountId, Money amount) {
        return create(UUID.randomUUID(), billingAccountId, amount);
    }

    /** With a chosen id: PaymentService derives it from the Idempotency-Key, so a retry finds the same row. */
    public static Payment create(UUID id, UUID billingAccountId, Money amount) {
        return create(id, billingAccountId, amount, FxRate.identity(amount.currency()));
    }

    /** Paid in {@code amount}'s currency, booked in {@code lockedRate.quote()} at {@code lockedRate}. */
    public static Payment create(UUID id, UUID billingAccountId, Money amount, FxRate lockedRate) {
        return new Payment(id, billingAccountId, amount, lockedRate);
    }

    /** Which card pays: set once, before authorization. */
    public void attachCard(CardInfo card) {
        if (cardToken != null) {
            throw new IllegalStateException("payment " + id + " already has a card");
        }
        this.cardToken = card.token();
        this.cardLast4 = card.last4();
        this.cardBrand = card.brand();
        this.cardFingerprint = card.fingerprint();
    }

    public void holdForReview() {
        moveTo(PaymentStatus.PENDING_REVIEW);
    }

    public void authorize(String processorReference) {
        authorize(processorReference, null);
    }

    public void authorize(String processorReference, String networkResponseCode) {
        if (processorReference == null || processorReference.isBlank()) {
            throw new IllegalArgumentException("processor reference is required");
        }
        moveTo(PaymentStatus.AUTHORIZED);
        this.processorReference = processorReference;
        this.networkResponseCode = networkResponseCode;
        this.authorizedAt = Instant.now();
    }

    public void fail(String reason) {
        fail(reason, null, false);
    }

    /**
     * Declined. {@code retryable}: the card was never judged (91 issuer unavailable), so a new attempt may succeed.
     * The payment itself still ends here (FAILED is terminal); a retry is a new payment with a new Idempotency-Key.
     */
    public void fail(String reason, String networkResponseCode, boolean retryable) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("failure reason is required");
        }
        moveTo(PaymentStatus.FAILED);
        this.failureReason = reason;
        this.networkResponseCode = networkResponseCode;
        this.retryable = retryable;
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
        checkRefund(refundAmount);
        this.refundedAmountMinor += refundAmount.amountMinor();
        moveTo(refundedAmountMinor == capturedAmountMinor ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED);
    }

    /** Throws exactly what refund() would, without changing anything. */
    public void checkRefund(Money refundAmount) {
        // State first, so "refund before capture" or "refund after full refund" is a 409, not an amount error.
        // Every state a refund can start from (CAPTURED, PARTIALLY_REFUNDED) can reach REFUNDED.
        PaymentStateMachine.requireMove(id, status, PaymentStatus.REFUNDED);
        requirePositiveInOurCurrency(refundAmount);
        if (Math.addExact(refundedAmountMinor, refundAmount.amountMinor()) > capturedAmountMinor) {
            throw new IllegalArgumentException("cannot refund " + refundAmount + ", only "
                    + getRefundableAmount() + " is left to refund");
        }
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
    public Currency getBookCurrency() { return bookCurrency; }
    /** The rate locked at authorization (payment currency -> book currency). */
    public FxRate getLockedRate() { return new FxRate(fxRateId, currency, bookCurrency, fxRate, "locked", createdAt); }
    public boolean isForeignCurrency() { return !currency.equals(bookCurrency); }
    public String getCardToken() { return cardToken; }
    public String getCardLast4() { return cardLast4; }
    public String getCardBrand() { return cardBrand; }
    public String getCardFingerprint() { return cardFingerprint; }
    public PaymentStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public String getNetworkResponseCode() { return networkResponseCode; }
    public boolean isRetryable() { return retryable; }
    public String getProcessorReference() { return processorReference; }
    public Instant getAuthorizedAt() { return authorizedAt; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
