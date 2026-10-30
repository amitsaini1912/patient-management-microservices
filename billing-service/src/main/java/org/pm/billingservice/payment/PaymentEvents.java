package org.pm.billingservice.payment;

import com.google.protobuf.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.pm.billingservice.outbox.OutboxWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import payment.events.PaymentEvent;

/**
 * Turns payment changes into {@link PaymentEvent}s in the outbox. Every method must be called inside the
 * transaction that makes the change (OutboxWriter enforces it), after the change is applied to {@code payment}.
 */
@Component
public class PaymentEvents {

    static final int SCHEMA_VERSION = 1;

    private final OutboxWriter outbox;
    private final Clock clock;
    private final String topic;

    public PaymentEvents(OutboxWriter outbox, Clock clock, @Value("${billing.events.payment-topic}") String topic) {
        this.outbox = outbox;
        this.clock = clock;
        this.topic = topic;
    }

    void authorized(Payment payment) {
        append("payment.authorized", payment, event -> {});
    }

    void failed(Payment payment) {
        append("payment.failed", payment, event -> {
            if (payment.getFailureReason() != null) {
                event.setFailureReason(payment.getFailureReason()); // protobuf setters reject null
            }
        });
    }

    void captured(Payment payment) {
        append("payment.captured", payment, event -> {});
    }

    void voided(Payment payment) {
        append("payment.voided", payment, event -> {});
    }

    void expired(Payment payment) {
        append("payment.expired", payment, event -> {});
    }

    void refunded(Payment payment, Refund refund) {
        append("payment.refunded", payment, event -> event
                .setRefundId(refund.getId().toString())
                .setRefundAmountMinor(refund.getAmount().amountMinor()));
    }

    private void append(String eventType, Payment payment, Consumer<PaymentEvent.Builder> details) {
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
    }
}
