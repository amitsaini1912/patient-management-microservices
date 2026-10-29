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
                payment.getCreatedAt(),
                payment.getUpdatedAt());
    }

    private static String decimal(Money money) {
        return money.toDecimal().toPlainString();
    }
}
