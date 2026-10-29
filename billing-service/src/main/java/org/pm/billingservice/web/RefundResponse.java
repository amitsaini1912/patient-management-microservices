package org.pm.billingservice.web;

import java.time.Instant;
import java.util.UUID;
import org.pm.billingservice.payment.Refund;

public record RefundResponse(UUID id, UUID paymentId, String amount, String currency, String reason, Instant createdAt) {

    public static RefundResponse from(Refund refund) {
        return new RefundResponse(
                refund.getId(),
                refund.getPaymentId(),
                refund.getAmount().toDecimal().toPlainString(),
                refund.getAmount().currency().getCurrencyCode(),
                refund.getReason(),
                refund.getCreatedAt());
    }
}
