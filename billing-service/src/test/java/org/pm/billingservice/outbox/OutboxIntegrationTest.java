package org.pm.billingservice.outbox;

import com.github.dockerjava.api.DockerClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.TestcontainersConfiguration;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.payment.Payment;
import org.pm.billingservice.payment.PaymentService;
import org.pm.billingservice.service.BillingAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;
import payment.events.PaymentEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Outbox end to end with real Postgres and real Kafka. The scheduled relay runs in the background like in
 * production, so tests wait for outcomes (DB row SENT, record on the topic) instead of calling it.
 */
@SpringBootTest(properties = "grpc.server.port=0") // same config as the other ITs -> Spring reuses one context
@Import(TestcontainersConfiguration.class)
class OutboxIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired private PaymentService paymentService;
    @Autowired private BillingAccountService billingAccountService;
    @Autowired private OutboxWriter outboxWriter;
    @Autowired private OutboxRelay relay;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private KafkaContainer kafkaContainer;
    @Value("${billing.events.payment-topic}") private String topic;

    private UUID billingAccountId;
    private KafkaConsumer<String, byte[]> consumer;

    @BeforeEach
    void setUp() {
        billingAccountId = billingAccountService
                .getOrCreateAccount(UUID.randomUUID(), "Test Patient", "patient@test.com").getId();
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new ByteArrayDeserializer());
        consumer.subscribe(List.of(topic));
    }

    @AfterEach
    void tearDown() {
        consumer.close();
    }

    @Test
    void committedPaymentChangesArriveOnKafkaInOrderKeyedByPayment() throws Exception {
        Payment payment = paymentService.authorize(newKey(), billingAccountId, inr(1_000_00), "pm_card_visa");
        paymentService.capture(payment.getId(), null);
        paymentService.refund(newKey(), payment.getId(), inr(300_00), "one item");
        paymentService.refund(newKey(), payment.getId(), inr(200_00), "another item");

        List<ConsumerRecord<String, byte[]>> records = recordsFor(payment.getId(), 4);

        List<PaymentEvent> events = parse(records);
        assertThat(events).extracting(PaymentEvent::getEventType)
                .containsExactly("payment.authorized", "payment.captured", "payment.refunded", "payment.refunded");
        assertThat(events).extracting(PaymentEvent::getRefundedAmountMinor).containsExactly(0L, 0L, 300_00L, 500_00L);
        PaymentEvent captured = events.get(1);
        assertThat(captured.getSchemaVersion()).isEqualTo(1);
        assertThat(captured.getStatus()).isEqualTo("CAPTURED");
        assertThat(captured.getAmountMinor()).isEqualTo(1_000_00);
        assertThat(captured.getCapturedAmountMinor()).isEqualTo(1_000_00);
        assertThat(captured.getCurrency()).isEqualTo("INR");
        assertThat(captured.getBillingAccountId()).isEqualTo(billingAccountId.toString());
        assertThat(events.get(2).getRefundAmountMinor()).isEqualTo(300_00);
        // event_id: in the payload, as a header, and as the outbox row id (that's what consumers dedup on)
        assertThat(header(records.get(1), "event_id")).isEqualTo(captured.getEventId());
        assertThat(header(records.get(1), "event_type")).isEqualTo("payment.captured");
        assertThat(status(UUID.fromString(captured.getEventId()))).isEqualTo("SENT");
    }

    @Test
    void declinedCardPublishesPaymentFailedWithReason() throws Exception {
        Payment payment = paymentService.authorize(newKey(), billingAccountId, inr(500_00), "pm_card_declined");

        PaymentEvent event = parse(recordsFor(payment.getId(), 1)).get(0);

        assertThat(event.getEventType()).isEqualTo("payment.failed");
        assertThat(event.getFailureReason()).isEqualTo("card_declined");
    }

    @Test
    void rolledBackTransactionLeavesNoEvent() {
        UUID eventId = UUID.randomUUID();

        transactionTemplate.executeWithoutResult(tx -> {
            outboxWriter.append(eventId, "payment", UUID.randomUUID(), "payment.captured", topic, "k", new byte[] {1});
            tx.setRollbackOnly(); // e.g. the ledger posting in the same transaction failed
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE id = ?", Integer.class, eventId)).isZero();
    }

    @Test
    void writingOutsideATransactionIsRefused() {
        // that would be a dual write again: the event could commit without the change, or the other way round
        assertThatThrownBy(() -> outboxWriter.append(UUID.randomUUID(), "payment", UUID.randomUUID(),
                "payment.captured", topic, "k", new byte[] {1}))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void retriedRefundAttemptsLeaveExactlyOneEventPerRefund() throws Exception {
        // concurrent refunds on one payment lose @Version races and retry; each lost attempt rolled back its event
        Payment payment = paymentService.authorize(newKey(), billingAccountId, inr(1_000_00), "pm_card_visa");
        paymentService.capture(payment.getId(), null);
        int refunds = 6;
        ExecutorService pool = Executors.newFixedThreadPool(refunds);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < refunds; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return paymentService.refund(newKey(), payment.getId(), inr(100_00), "concurrent");
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'payment.refunded'",
                Integer.class, payment.getId())).isEqualTo(refunds);
        // the last refund event carries the full total
        List<PaymentEvent> events = parse(recordsFor(payment.getId(), 2 + refunds));
        assertThat(events.get(events.size() - 1).getRefundedAmountMinor()).isEqualTo(600_00);
    }

    @Test
    void kafkaDownPaymentsStillWorkEventsWaitAndAreSentLater() throws Exception {
        DockerClient docker = DockerClientFactory.instance().client();
        Payment payment = paymentService.authorize(newKey(), billingAccountId, inr(1_000_00), "pm_card_visa");
        recordsFor(payment.getId(), 1); // relay is healthy before we break Kafka

        docker.pauseContainerCmd(kafkaContainer.getContainerId()).exec();
        UUID capturedEventId;
        try {
            paymentService.capture(payment.getId(), null); // works: it only writes Postgres
            capturedEventId = jdbc.queryForObject(
                    "SELECT id FROM outbox_event WHERE aggregate_id = ? AND event_type = 'payment.captured'",
                    UUID.class, payment.getId());

            // the relay tries, times out, counts the attempt, backs off; the row stays PENDING
            await(() -> attempts(capturedEventId) >= 1);
            assertThat(status(capturedEventId)).isEqualTo("PENDING");
            assertThat(lastError(capturedEventId)).contains("Timeout");
            assertThat(relay.pausedUntil()).isAfter(Instant.now().minusSeconds(1));
        } finally {
            docker.unpauseContainerCmd(kafkaContainer.getContainerId()).exec();
        }

        // Kafka is back: the waiting event goes out on its own
        await(() -> "SENT".equals(status(capturedEventId)));
        List<PaymentEvent> events = parse(drain(payment.getId(), Duration.ofSeconds(5)));
        // At least once, maybe twice: the paused broker still had our first (timed-out) send in its socket and
        // wrote it on unpause, then our retry arrived too. Every copy has the same event_id -> consumers dedup.
        assertThat(events).isNotEmpty()
                .allSatisfy(e -> assertThat(e.getEventId()).isEqualTo(capturedEventId.toString()))
                .extracting(PaymentEvent::getEventType).containsOnly("payment.captured");
    }

    @Test
    void aRecordKafkaWillNeverAcceptIsParkedAndDoesNotBlockTheRest() throws Exception {
        UUID tooBig = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(tx -> outboxWriter.append(tooBig, "payment", UUID.randomUUID(),
                "payment.captured", topic, "k", new byte[2 * 1024 * 1024])); // > max.request.size (1 MB)
        Payment after = paymentService.authorize(newKey(), billingAccountId, inr(1_000_00), "pm_card_visa");

        await(() -> "FAILED".equals(status(tooBig)));
        assertThat(lastError(tooBig)).contains("RecordTooLargeException");
        assertThat(parse(recordsFor(after.getId(), 1)).get(0).getEventType()).isEqualTo("payment.authorized");
    }

    @Test
    void onlyOneInstanceRelaysAtATime() throws Exception {
        // another "instance" is in the middle of relaying (holds the advisory lock)
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService otherInstance = Executors.newSingleThreadExecutor();
        Future<?> holder = otherInstance.submit(() -> transactionTemplate.executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, 0x6f7574626f78L);
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        try {
            locked.await(10, TimeUnit.SECONDS);
            Payment payment = paymentService.authorize(newKey(), billingAccountId, inr(1_000_00), "pm_card_visa");

            assertThat(relay.relayBatch()).isZero(); // didn't wait, didn't send
            UUID eventId = jdbc.queryForObject("SELECT id FROM outbox_event WHERE aggregate_id = ?", UUID.class, payment.getId());
            assertThat(status(eventId)).isEqualTo("PENDING");
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            otherInstance.shutdownNow();
        }
    }

    // ---- helpers ----

    /** Polls the topic until {@code count} records with this payment's key arrived, in partition order. */
    private List<ConsumerRecord<String, byte[]>> recordsFor(UUID paymentId, int count) {
        List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
        Instant deadline = Instant.now().plus(WAIT);
        while (found.size() < count && Instant.now().isBefore(deadline)) {
            for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(200))) {
                if (paymentId.toString().equals(record.key())) {
                    found.add(record);
                }
            }
        }
        assertThat(found).as("records for payment %s", paymentId).hasSize(count);
        return found;
    }

    /** Whatever else arrives for this payment within {@code duration} (e.g. duplicates). */
    private List<ConsumerRecord<String, byte[]>> drain(UUID paymentId, Duration duration) {
        List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
        Instant deadline = Instant.now().plus(duration);
        while (Instant.now().isBefore(deadline)) {
            for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(200))) {
                if (paymentId.toString().equals(record.key())) {
                    found.add(record);
                }
            }
        }
        return found;
    }

    private static List<PaymentEvent> parse(List<ConsumerRecord<String, byte[]>> records) throws Exception {
        List<PaymentEvent> events = new ArrayList<>();
        for (ConsumerRecord<String, byte[]> record : records) {
            events.add(PaymentEvent.parseFrom(record.value()));
        }
        return events;
    }

    private static String header(ConsumerRecord<String, byte[]> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private String status(UUID eventId) {
        return jdbc.queryForObject("SELECT status FROM outbox_event WHERE id = ?", String.class, eventId);
    }

    private int attempts(UUID eventId) {
        return jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE id = ?", Integer.class, eventId);
    }

    private String lastError(UUID eventId) {
        return jdbc.queryForObject("SELECT last_error FROM outbox_event WHERE id = ?", String.class, eventId);
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

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static Money inr(long paise) {
        return Money.ofMinor(paise, "INR");
    }
}
