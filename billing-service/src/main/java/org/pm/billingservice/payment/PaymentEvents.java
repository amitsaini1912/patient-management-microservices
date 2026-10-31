package org.pm.billingservice.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.google.protobuf.Timestamp;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.pm.billingservice.audit.AuditLog;
import org.pm.billingservice.outbox.OutboxWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import payment.events.PaymentEvent;

/**
 * Turns payment changes into {@link PaymentEvent}s in the outbox AND rows in the audit log (Day 13, ADR 0016).
 * Day 19b (ADR 0018): two JSON events for the risk engine go through the same outbox - `chargeback.created` and
 * `payment.outcome` (captured / review approved / review rejected) - per the schemas in razorpay-risk-agent's
 * docs/contracts/. Key = payment id = the risk engine's transaction_id.
 * Day 20b (ADR 0020): every payment event is also counted, billing_payment_events_total{type, reason} - AFTER the
 * transaction commits (a rolled-back or retried attempt must not count twice). Payments/s and approval rate come
 * from it.
 * Every method must be called inside the transaction that makes the change (OutboxWriter and AuditLog enforce it),
 * after the change is applied to {@code payment}. Every payment change goes through here, so nothing escapes the audit.
 */
@Component
public class PaymentEvents {

    static final int SCHEMA_VERSION = 1;

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record ChargebackCreated(String eventId, String transactionId, long amountMinor, String currency, String reason,
                             String createdAt, int schemaVersion) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record PaymentOutcome(String eventId, String transactionId, String outcome, long amountMinor, String currency,
                          String occurredAt, int schemaVersion) {}

    private final OutboxWriter outbox;
    private final AuditLog audit;
    private final Clock clock;
    private final ObjectMapper json;
    private final MeterRegistry meters;
    private final String topic;
    private final String chargebackTopic;
    private final String outcomeTopic;

    public PaymentEvents(OutboxWriter outbox, AuditLog audit, Clock clock, ObjectMapper json, MeterRegistry meters,
                         @Value("${billing.events.payment-topic}") String topic,
                         @Value("${billing.events.chargeback-topic}") String chargebackTopic,
                         @Value("${billing.events.outcome-topic}") String outcomeTopic) {
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
        this.json = json;
        this.meters = meters;
        this.topic = topic;
        this.chargebackTopic = chargebackTopic;
        this.outcomeTopic = outcomeTopic;
    }

    void authorized(Payment payment) {
        append("payment.authorized", payment, event -> {}, Map.of());
    }

    void failed(Payment payment) {
        append("payment.failed", payment, event -> {
            if (payment.getFailureReason() != null) {
                event.setFailureReason(payment.getFailureReason()); // protobuf setters reject null
            }
        }, Map.of());
    }

    void heldForReview(Payment payment) {
        append("payment.held_for_review", payment, event -> {}, Map.of());
    }

    void captured(Payment payment) {
        append("payment.captured", payment, event -> {}, Map.of());
        outcome(payment, "captured", payment.getCapturedAmount().amountMinor());
    }

    /** A person decided on a held payment (the authorized/failed event that follows is separate). */
    void reviewed(Payment payment) {
        outcome(payment, payment.getReviewDecision() == Payment.ReviewDecision.APPROVED ? "review_approved" : "review_rejected",
                payment.getAmount().amountMinor());
    }

    void chargedBack(Payment payment, Chargeback chargeback) {
        UUID eventId = UUID.randomUUID();
        ChargebackCreated event = new ChargebackCreated(eventId.toString(), payment.getId().toString(),
                chargeback.getAmount().amountMinor(), chargeback.getAmount().currency().getCurrencyCode(),
                chargeback.getReason(), iso(chargeback.getCreatedAt()), SCHEMA_VERSION);
        outbox.append(eventId, "payment", payment.getId(), "chargeback.created", chargebackTopic, payment.getId().toString(),
                bytes(event));
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("chargeback_id", chargeback.getId().toString());
        extras.put("chargeback_amount_minor", chargeback.getAmount().amountMinor());
        extras.put("chargeback_reason", chargeback.getReason());
        extras.put("case_reference", chargeback.getCaseReference());
        audit.append("payment.charged_back", "payment", payment.getId().toString(), auditDetails(eventId, payment, extras));
    }

    private void outcome(Payment payment, String outcome, long amountMinor) {
        UUID eventId = UUID.randomUUID();
        PaymentOutcome event = new PaymentOutcome(eventId.toString(), payment.getId().toString(), outcome, amountMinor,
                payment.getAmount().currency().getCurrencyCode(), iso(clock.instant()), SCHEMA_VERSION);
        outbox.append(eventId, "payment", payment.getId(), "payment.outcome", outcomeTopic, payment.getId().toString(),
                bytes(event));
    }

    /** Millisecond precision, UTC: Java has nanoseconds, Postgres keeps microseconds, Python parses up to micro. */
    static String iso(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private byte[] bytes(Object event) {
        try {
            return json.writeValueAsBytes(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    void voided(Payment payment) {
        append("payment.voided", payment, event -> {}, Map.of());
    }

    void expired(Payment payment) {
        append("payment.expired", payment, event -> {}, Map.of());
    }

    void refunded(Payment payment, Refund refund) {
        append("payment.refunded", payment, event -> event
                .setRefundId(refund.getId().toString())
                .setRefundAmountMinor(refund.getAmount().amountMinor()),
                Map.of("refund_id", refund.getId().toString(), "refund_amount_minor", refund.getAmount().amountMinor()));
    }

    private void append(String eventType, Payment payment, Consumer<PaymentEvent.Builder> details,
                        Map<String, Object> auditExtras) {
        UUID eventId = UUID.randomUUID();
        Instant now = clock.instant();
        PaymentEvent.Builder event = PaymentEvent.newBuilder()
                .setEventId(eventId.toString())
                .setEventType(eventType)
                .setSchemaVersion(SCHEMA_VERSION)
                .setOccurredAt(Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()))
                .setPaymentId(payment.getId().toString())
                .setBillingAccountId(payment.getBillingAccountId().toString())
                .setAmountMinor(payment.getAmount().amountMinor())
                .setCurrency(payment.getAmount().currency().getCurrencyCode())
                .setStatus(payment.getStatus().name())
                .setCapturedAmountMinor(payment.getCapturedAmount().amountMinor())
                .setRefundedAmountMinor(payment.getRefundedAmount().amountMinor());
        details.accept(event);
        outbox.append(eventId, "payment", payment.getId(), eventType, topic, payment.getId().toString(),
                event.build().toByteArray());
        audit.append(eventType, "payment", payment.getId().toString(), auditDetails(eventId, payment, auditExtras));
        countAfterCommit(eventType, "payment.failed".equals(eventType) ? payment.getFailureReason() : null);
    }

    /** reason: why a payment FAILED (card_declined, risk_declined, ...: a small, fixed set); "none" otherwise. */
    private void countAfterCommit(String eventType, String reason) {
        String reasonTag = reason == null ? "none" : reason;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                meters.counter("billing.payment.events", "type", eventType, "reason", reasonTag).increment();
            }
        });
    }

    /** The payment after the change. Card: last4/brand only (never a number; the token is not needed for audit). */
    private static Map<String, Object> auditDetails(UUID eventId, Payment payment, Map<String, Object> extras) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("event_id", eventId.toString());
        details.put("billing_account_id", payment.getBillingAccountId().toString());
        details.put("status", payment.getStatus().name());
        details.put("currency", payment.getAmount().currency().getCurrencyCode());
        details.put("amount_minor", payment.getAmount().amountMinor());
        details.put("captured_minor", payment.getCapturedAmount().amountMinor());
        details.put("refunded_minor", payment.getRefundedAmount().amountMinor());
        details.put("charged_back_minor", payment.getChargedBackAmount().amountMinor());
        details.put("failure_reason", payment.getFailureReason());
        details.put("network_response_code", payment.getNetworkResponseCode());
        details.put("processor_reference", payment.getProcessorReference());
        details.put("card_last4", payment.getCardLast4());
        details.put("card_brand", payment.getCardBrand());
        // Day 19: what the risk check decided, and the manual review of a held payment (who, when, why)
        details.put("risk_decision", payment.getRiskDecision() == null ? null : payment.getRiskDecision().name());
        details.put("risk_source", payment.getRiskSource() == null ? null : payment.getRiskSource().name());
        details.put("risk_reason_codes", payment.getRiskReasonCodes());
        details.put("review_decision", payment.getReviewDecision() == null ? null : payment.getReviewDecision().name());
        details.put("reviewed_by", payment.getReviewedBy());
        details.put("review_note", payment.getReviewNote());
        details.putAll(extras);
        return details;
    }
}
