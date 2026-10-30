package org.pm.analyticservice.payment;

import com.google.protobuf.InvalidProtocolBufferException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.pm.analyticservice.kafka.InvalidEventException;
import org.pm.analyticservice.payment.PaymentEventStore.PaymentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import payment.events.PaymentEvent;

/**
 * Idempotent consumer of billing-service's payment events.
 *
 * Billing's outbox delivers AT LEAST ONCE, and Kafka redelivers anything whose offset wasn't committed
 * (e.g. we crash after our DB commit). So: one transaction does "remember event_id" + "apply it"; a second
 * delivery of the same event finds the event_id and changes nothing.
 */
@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final PaymentEventStore store;
    private final Clock clock;

    public PaymentEventConsumer(PaymentEventStore store) {
        this.store = store;
        this.clock = Clock.systemUTC();
    }

    @KafkaListener(topics = "${analytics.kafka.payment-topic}")
    @Transactional
    public void onPaymentEvent(ConsumerRecord<String, byte[]> record) {
        PaymentEvent event = parse(record.value());
        UUID eventId = uuid("event_id", event.getEventId());
        Instant now = clock.instant();

        if (!store.markProcessed(eventId, record.topic(), now)) {
            log.info("duplicate payment event {} ({}) ignored", eventId, event.getEventType());
            return;
        }

        Instant occurredAt = Instant.ofEpochSecond(event.getOccurredAt().getSeconds(), event.getOccurredAt().getNanos());
        store.upsertProjection(new PaymentState(
                uuid("payment_id", event.getPaymentId()),
                uuid("billing_account_id", event.getBillingAccountId()),
                event.getStatus(), event.getCurrency(), event.getAmountMinor(),
                event.getCapturedAmountMinor(), event.getRefundedAmountMinor(),
                event.getFailureReason().isEmpty() ? null : event.getFailureReason(),
                event.getEventType(), occurredAt), now);

        switch (event.getEventType()) {
            case "payment.captured" ->
                    store.addToDailySummary(occurredAt, event.getCurrency(), 1, event.getCapturedAmountMinor(), 0, 0);
            case "payment.refunded" ->
                    store.addToDailySummary(occurredAt, event.getCurrency(), 0, 0, 1, event.getRefundAmountMinor());
            default -> { } // authorized / failed / voided / expired (and future types) only change the state
        }
    }

    private static PaymentEvent parse(byte[] value) {
        if (value == null) {
            throw new InvalidEventException("payment event has no value");
        }
        try {
            PaymentEvent event = PaymentEvent.parseFrom(value);
            if (event.getSchemaVersion() != 1) {
                throw new InvalidEventException("unsupported schema_version " + event.getSchemaVersion());
            }
            if (event.getEventType().isEmpty() || event.getCurrency().length() != 3) {
                throw new InvalidEventException("payment event without event_type or currency");
            }
            return event;
        } catch (InvalidProtocolBufferException e) {
            throw new InvalidEventException("payment event is not a valid PaymentEvent", e);
        }
    }

    /** Protobuf happily parses some random bytes into a message with empty fields; the ids catch that. */
    private static UUID uuid(String field, String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidEventException(field + " is not a UUID: '" + value + "'", e);
        }
    }
}
