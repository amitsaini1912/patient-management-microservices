package org.pm.billingservice.web;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
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
        // Day 19b: taken back by chargebacks (no longer refundable)
        String chargedBackAmount,
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
        Instant updatedAt,
        // Day 19: the risk check (null for payments made before it) and the manual review of a held payment
        Risk risk,
        Review review) {

    /** source ENGINE = the risk engine decided; FALLBACK = billing's own rules (see fallbackReason in the logs). */
    public record Risk(String decision, String source, List<String> reasonCodes, Double score, String modelVersion,
                       String rulesVersion) {}

    public record Review(String decision, String reviewedBy, Instant reviewedAt, String note) {}

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getBillingAccountId(),
                payment.getStatus(),
                payment.getAmount().currency().getCurrencyCode(),
                decimal(payment.getAmount()),
                decimal(payment.getCapturedAmount()),
                decimal(payment.getRefundedAmount()),
                decimal(payment.getChargedBackAmount()),
                payment.getFailureReason(),
                payment.getNetworkResponseCode(),
                payment.isRetryable(),
                payment.getBookCurrency().getCurrencyCode(),
                payment.getLockedRate().rate().stripTrailingZeros().toPlainString(),
                decimal(payment.getLockedRate().convert(payment.getAmount())),
                payment.getCardLast4(),
                payment.getCardBrand(),
                payment.getCreatedAt(),
                payment.getUpdatedAt(),
                payment.getRiskDecision() == null ? null : new Risk(payment.getRiskDecision().name(),
                        payment.getRiskSource().name(), codes(payment.getRiskReasonCodes()), payment.getRiskScore(),
                        payment.getRiskModelVersion(), payment.getRiskRulesVersion()),
                payment.getReviewDecision() == null ? null : new Review(payment.getReviewDecision().name(),
                        payment.getReviewedBy(), payment.getReviewedAt(), payment.getReviewNote()));
    }

    private static List<String> codes(String joined) {
        return joined == null || joined.isEmpty() ? List.of() : Arrays.asList(joined.split(","));
    }

    private static String decimal(Money money) {
        return money.toDecimal().toPlainString();
    }
}
