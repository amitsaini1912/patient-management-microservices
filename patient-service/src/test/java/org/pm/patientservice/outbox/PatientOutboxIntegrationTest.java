package org.pm.patientservice.outbox;

import com.github.dockerjava.api.DockerClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.pm.patientservice.TestcontainersConfiguration;
import org.pm.patientservice.dto.PatientRequestDTO;
import org.pm.patientservice.dto.PatientResponseDTO;
import org.pm.patientservice.exception.EmailAlreadyExistsException;
import org.pm.patientservice.grpc.BillingServiceGrpcClient;
import org.pm.patientservice.service.PatientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;
import patient.events.PatientEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR 0030: a patient and its PATIENT_CREATED event are committed together, and the event reaches Kafka even if
 * Kafka was down when the patient was created (before: the event was logged and lost).
 * The relay is called by hand (the scheduled one is slowed down to once a minute here).
 */
@SpringBootTest(properties = {"grpc.server.port=0", "patient.outbox.relay-delay=60s", "spring.kafka.producer.properties.max.block.ms=2000",
        "spring.kafka.producer.properties.delivery.timeout.ms=3000", "spring.kafka.producer.properties.request.timeout.ms=2000",
        "patient.outbox.send-timeout=4s"})
@Import(TestcontainersConfiguration.class)
class PatientOutboxIntegrationTest {

    @Autowired private PatientService patientService;
    @Autowired private OutboxRelay relay;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private KafkaContainer kafka;
    @MockitoBean private BillingServiceGrpcClient billing; // billing-service is not what's tested here

    @Test
    void createdPatientIsWrittenWithItsEventAndTheRelayPublishesItKeyedByPatientId() {
        PatientResponseDTO created = patientService.createPatient(request());
        UUID id = UUID.fromString(created.getId());
        assertThat(pending(id)).isEqualTo(1);                   // committed with the patient, not sent yet

        relay.publishPending();

        assertThat(pending(id)).isZero();
        ConsumerRecord<String, byte[]> record = consume(id.toString());
        assertThat(record.key()).isEqualTo(id.toString());
        PatientEvent event = parse(record.value());
        assertThat(event.getEventType()).isEqualTo("PATIENT_CREATED");
        assertThat(event.getEmail()).isEqualTo(created.getEmail());
    }

    @Test
    void aRefusedPatientLeavesNoEvent() {
        PatientRequestDTO first = request();
        patientService.createPatient(first);
        long before = jdbc.queryForObject("SELECT count(*) FROM patient_event_outbox", Long.class);

        assertThatThrownBy(() -> patientService.createPatient(sameEmail(first))).isInstanceOf(EmailAlreadyExistsException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM patient_event_outbox", Long.class)).isEqualTo(before);
    }

    @Test
    void kafkaDownTheEventWaitsWithAttemptsAndIsPublishedWhenKafkaIsBack() {
        // a producer that is already connected (metadata cached): its send() is accepted into the buffer and only fails
        // LATER - the case where marking the row before Kafka's ack would lose the event. (With a cold producer the
        // send throws at once and even a fire-and-forget relay "notices"; that hid a broken relay in the first version.)
        UUID warm = UUID.fromString(patientService.createPatient(request()).getId());
        relay.publishPending();
        assertThat(pending(warm)).isZero();
        DockerClient docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(kafka.getContainerId()).exec();
        PatientResponseDTO created;
        try {
            created = patientService.createPatient(request()); // the patient is saved: Kafka isn't needed for that
            relay.publishPending();
        } finally {
            docker.unpauseContainerCmd(kafka.getContainerId()).exec();
        }
        UUID id = UUID.fromString(created.getId());
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT attempts, last_error, published_at FROM patient_event_outbox WHERE patient_id = ?", id);
        assertThat(row.get("published_at")).isNull();
        assertThat((Integer) row.get("attempts")).isEqualTo(1);
        assertThat(row.get("last_error")).isNotNull();

        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (pending(id) > 0 && System.nanoTime() < deadline) {
            relay.publishPending();
        }
        assertThat(pending(id)).isZero();
        assertThat(parse(consume(id.toString()).value()).getPatientId()).isEqualTo(id.toString());
    }

    // ---- helpers ----

    private long pending(UUID patientId) {
        return jdbc.queryForObject("SELECT count(*) FROM patient_event_outbox WHERE patient_id = ? AND published_at IS NULL",
                Long.class, patientId);
    }

    private ConsumerRecord<String, byte[]> consume(String key) {
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of("patient"));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("no event for " + key + " on topic patient");
    }

    private static PatientEvent parse(byte[] value) {
        try {
            return PatientEvent.parseFrom(value);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static PatientRequestDTO request() {
        PatientRequestDTO dto = new PatientRequestDTO();
        dto.setName("Outbox Patient");
        dto.setEmail("outbox-" + UUID.randomUUID() + "@test.com");
        dto.setAddress("1 Test Street");
        dto.setDateOfBirth("1990-01-01");
        dto.setRegisteredDate("2026-11-01");
        return dto;
    }

    private static PatientRequestDTO sameEmail(PatientRequestDTO other) {
        PatientRequestDTO dto = request();
        dto.setEmail(other.getEmail());
        return dto;
    }
}
