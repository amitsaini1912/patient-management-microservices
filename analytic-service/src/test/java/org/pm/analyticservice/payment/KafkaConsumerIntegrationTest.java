package org.pm.analyticservice.payment;

import com.google.protobuf.Timestamp;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.pm.analyticservice.TestcontainersConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;
import patient.events.PatientEvent;
import payment.events.PaymentEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both listeners against real Kafka + Postgres: dedup, ordering guard, and the dead-letter topics.
 * We produce records by hand (billing-service isn't running); the bytes are what billing's outbox sends.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=true",                 // test/config/application.properties turns these off
        "spring.kafka.admin.auto-create=true",
        "spring.datasource.driver-class-name=org.postgresql.Driver", // ... and points at H2
        "analytics.kafka.retry-interval=100ms"})
@Import(TestcontainersConfiguration.class)
class KafkaConsumerIntegrationTest {

    private static final String TOPIC = "payment.events";
    private static final Duration WAIT = Duration.ofSeconds(30);
    // events of one test may still be in flight when the next starts; daily totals are per day, so each test
    // gets its own day and can't see another test's counts
    private static final AtomicInteger DAYS = new AtomicInteger();

    @Autowired private KafkaTemplate<String, byte[]> kafka;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private KafkaContainer kafkaContainer;

    @Test
    void capturedEventUpdatesProjectionAndDailyTotals() throws Exception {
        UUID paymentId = UUID.randomUUID();
        Instant at = ownDay();
        Totals before = totals(at, "INR");

        send(event(paymentId, "payment.authorized", "AUTHORIZED", at.minusSeconds(60), 0, 0).build());
        send(event(paymentId, "payment.captured", "CAPTURED", at, 1_000_00, 0).build());

        await(() -> "CAPTURED".equals(status(paymentId)));
        assertThat(jdbc.queryForObject("SELECT captured_amount_minor FROM payment_projection WHERE payment_id = ?",
                Long.class, paymentId)).isEqualTo(1_000_00L);
        assertThat(totals(at, "INR")).isEqualTo(before.plusCaptured(1_000_00));
    }

    @Test
    void theSameEventTwiceIsAppliedOnce() throws Exception {
        UUID paymentId = UUID.randomUUID();
        Instant at = ownDay();
        Totals before = totals(at, "INR");
        PaymentEvent captured = event(paymentId, "payment.captured", "CAPTURED", at, 700_00, 0).build();
        // a type this consumer doesn't know yet (forward compatible: state only). Same key = same partition,
        // so once it's processed, the duplicate before it has been consumed too.
        PaymentEvent later = event(paymentId, "payment.disputed", "DISPUTED", at.plusSeconds(1), 700_00, 0).build();

        send(captured);
        send(captured); // what billing's outbox did in its Kafka-pause test: same event_id, twice
        send(later);

        await(() -> processed(later.getEventId()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?::uuid",
                Integer.class, captured.getEventId())).isEqualTo(1);
        assertThat(totals(at, "INR")).isEqualTo(before.plusCaptured(700_00)); // not 1400
        assertThat(status(paymentId)).isEqualTo("DISPUTED");
    }

    @Test
    void anOlderEventArrivingLateDoesNotMoveStatusBackwards() throws Exception {
        UUID paymentId = UUID.randomUUID();
        Instant at = ownDay();

        send(event(paymentId, "payment.captured", "CAPTURED", at, 500_00, 0).build());
        await(() -> "CAPTURED".equals(status(paymentId)));
        PaymentEvent lateAuthorized = event(paymentId, "payment.authorized", "AUTHORIZED", at.minusSeconds(5), 0, 0).build();
        send(lateAuthorized);

        await(() -> processed(lateAuthorized.getEventId()));
        assertThat(status(paymentId)).isEqualTo("CAPTURED");
    }

    @Test
    void refundsAddUpInTheDailyTotals() throws Exception {
        UUID paymentId = UUID.randomUUID();
        Instant at = ownDay();
        Totals before = totals(at, "INR");

        send(event(paymentId, "payment.captured", "CAPTURED", at, 1_000_00, 0).build());
        send(event(paymentId, "payment.refunded", "PARTIALLY_REFUNDED", at.plusMillis(1), 1_000_00, 300_00)
                .setRefundId(UUID.randomUUID().toString()).setRefundAmountMinor(300_00).build());
        send(event(paymentId, "payment.refunded", "PARTIALLY_REFUNDED", at.plusMillis(2), 1_000_00, 500_00)
                .setRefundId(UUID.randomUUID().toString()).setRefundAmountMinor(200_00).build());

        await(() -> jdbc.query("SELECT refunded_amount_minor FROM payment_projection WHERE payment_id = ?",
                (rs, i) -> rs.getLong(1), paymentId).contains(500_00L));
        assertThat(totals(at, "INR")).isEqualTo(before.plusCaptured(1_000_00).plusRefunds(2, 500_00));
    }

    @Test
    void poisonPaymentEventGoesToTheDltAndTheConsumerKeepsGoing() throws Exception {
        UUID paymentId = UUID.randomUUID();
        byte[] garbage = "definitely not protobuf".getBytes(StandardCharsets.UTF_8);
        try (KafkaConsumer<String, byte[]> dlt = consumer(TOPIC + ".DLT")) {
            kafka.send(TOPIC, paymentId.toString(), garbage).get();
            // the next event on the SAME partition (same key) must still be processed
            send(event(paymentId, "payment.authorized", "AUTHORIZED", Instant.now(), 0, 0).build());

            await(() -> "AUTHORIZED".equals(status(paymentId)));
            ConsumerRecord<String, byte[]> parked = firstWithKey(dlt, paymentId.toString());
            assertThat(parked.value()).isEqualTo(garbage);                    // the original bytes, untouched
            assertThat(header(parked, "kafka_dlt-original-topic")).isEqualTo(TOPIC);
            assertThat(header(parked, "kafka_dlt-exception-fqcn"))
                    .contains("ListenerExecutionFailedException");
            assertThat(header(parked, "kafka_dlt-exception-cause-fqcn"))
                    .isEqualTo("org.pm.analyticservice.kafka.InvalidEventException");
        }
    }

    @Test
    void poisonPatientEventGoesToPatientDltInsteadOfBeingDropped() throws Exception {
        String key = "poison-" + UUID.randomUUID();
        // valid protobuf, invalid content: patientId is not a UUID
        byte[] bad = PatientEvent.newBuilder().setPatientId("not-a-uuid").setName("X").setEmail("x@x.com").build().toByteArray();
        UUID goodId = UUID.randomUUID();
        try (KafkaConsumer<String, byte[]> dlt = consumer("patient.DLT")) {
            kafka.send("patient", key, bad).get();
            kafka.send("patient", key, PatientEvent.newBuilder().setPatientId(goodId.toString())
                    .setName("Good").setEmail("good@x.com").build().toByteArray()).get();

            await(() -> jdbc.queryForObject("SELECT count(*) FROM patient WHERE id = ?", Integer.class, goodId) == 1);
            assertThat(firstWithKey(dlt, key).value()).isEqualTo(bad);
        }
    }

    // ---- helpers ----

    private static Instant ownDay() {
        return Instant.parse("2020-01-01T12:00:00Z").plus(Duration.ofDays(DAYS.incrementAndGet()));
    }

    private static PaymentEvent.Builder event(UUID paymentId, String type, String status, Instant at,
                                              long captured, long refunded) {
        return PaymentEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setEventType(type)
                .setSchemaVersion(1)
                .setOccurredAt(Timestamp.newBuilder().setSeconds(at.getEpochSecond()).setNanos(at.getNano()))
                .setPaymentId(paymentId.toString())
                .setBillingAccountId(UUID.randomUUID().toString())
                .setAmountMinor(1_000_00)
                .setCurrency("INR")
                .setStatus(status)
                .setCapturedAmountMinor(captured)
                .setRefundedAmountMinor(refunded);
    }

    private void send(PaymentEvent event) throws Exception {
        kafka.send(TOPIC, event.getPaymentId(), event.toByteArray()).get(); // key = payment id, like billing
    }

    private String status(UUID paymentId) {
        return jdbc.query("SELECT status FROM payment_projection WHERE payment_id = ?",
                (rs, i) -> rs.getString(1), paymentId).stream().findFirst().orElse(null);
    }

    private boolean processed(String eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?::uuid", Integer.class, eventId) > 0;
    }

    private record Totals(int capturedCount, long capturedMinor, int refundCount, long refundedMinor) {
        Totals plusCaptured(long minor) {
            return new Totals(capturedCount + 1, capturedMinor + minor, refundCount, refundedMinor);
        }

        Totals plusRefunds(int count, long minor) {
            return new Totals(capturedCount, capturedMinor, refundCount + count, refundedMinor + minor);
        }
    }

    private Totals totals(Instant at, String currency) {
        LocalDate day = at.atZone(ZoneOffset.UTC).toLocalDate();
        return jdbc.query("""
                        SELECT captured_count, captured_minor, refund_count, refunded_minor
                        FROM payment_daily_summary WHERE summary_date = ? AND currency = ?""",
                (rs, i) -> new Totals(rs.getInt(1), rs.getLong(2), rs.getInt(3), rs.getLong(4)),
                java.sql.Date.valueOf(day), currency).stream().findFirst().orElse(new Totals(0, 0, 0, 0));
    }

    private KafkaConsumer<String, byte[]> consumer(String topic) {
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new ByteArrayDeserializer());
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    private static ConsumerRecord<String, byte[]> firstWithKey(KafkaConsumer<String, byte[]> consumer, String key) {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(200))) {
                if (key.equals(record.key())) {
                    return record;
                }
            }
        }
        throw new AssertionError("no record with key " + key + " within " + WAIT);
    }

    private static String header(ConsumerRecord<String, byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met within " + WAIT);
            }
            Thread.sleep(100);
        }
    }
}
