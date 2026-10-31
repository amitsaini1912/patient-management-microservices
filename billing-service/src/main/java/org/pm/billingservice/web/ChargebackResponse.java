package org.pm.billingservice.web;

import java.time.Instant;
import java.util.UUID;
import org.pm.billingservice.payment.Chargeback;

public record ChargebackResponse(UUID id, UUID paymentId, String amount, String currency, String reason,
                                 String caseReference, Instant createdAt) {

    public static ChargebackResponse from(Chargeback chargeback) {
        return new ChargebackResponse(
                chargeback.getId(),
                chargeback.getPaymentId(),
                chargeback.getAmount().toDecimal().toPlainString(),
                chargeback.getAmount().currency().getCurrencyCode(),
                chargeback.getReason(),
                chargeback.getCaseReference(),
                chargeback.getCreatedAt());
    }
}
