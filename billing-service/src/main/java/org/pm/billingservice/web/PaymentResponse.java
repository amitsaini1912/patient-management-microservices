package org.pm.billingservice.web;

import java.time.Instant;
import java.util.UUID;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.payment.Payment;
import org.pm.billingservice.payment.PaymentStatus;

/** Amounts as decimal strings in the payment's currency, the same format the request uses. */
public record PaymentResponse(
        UUID id,
        UUID billingAccountId,
        PaymentStatus status,
        String currency,
        String amount,
        String capturedAmount,
        String refundedAmount,
        String failureReason,
        // Day 11: the card network's answer (ISO 8583 field 39) and whether a FAILED payment is worth a NEW attempt
        // (new Idempotency-Key; the same key returns this payment again)
        String networkResponseCode,
        boolean retryable,
        // Day 9: the books' currency, the rate locked at authorization, and the amount in book currency at that rate
        String bookCurrency,
        String fxRate,
        String bookAmount,
        // Day 10: which card (never the number; the fingerprint stays internal)
        String cardLast4,
        String cardBrand,
        Instant createdAt,
        Instant updatedAt) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getBillingAccountId(),
                payment.getStatus(),
                payment.getAmount().currency().getCurrencyCode(),
                decimal(payment.getAmount()),
                decimal(payment.getCapturedAmount()),
                decimal(payment.getRefundedAmount()),
                payment.getFailureReason(),
                payment.getNetworkResponseCode(),
                payment.isRetryable(),
                payment.getBookCurrency().getCurrencyCode(),
                payment.getLockedRate().rate().stripTrailingZeros().toPlainString(),
                decimal(payment.getLockedRate().convert(payment.getAmount())),
                payment.getCardLast4(),
                payment.getCardBrand(),
                payment.getCreatedAt(),
                payment.getUpdatedAt());
    }

    private static String decimal(Money money) {
        return money.toDecimal().toPlainString();
    }
}
