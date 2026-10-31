package org.pm.billingservice.outbox;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.TestcontainersConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 20a (ADR 0019): the request's trace context survives the outbox - stored with the row, sent as the Kafka
 * traceparent header by the (scheduled, other-thread) relay. A real SDK tracer stands in for the Java agent.
 */
@SpringBootTest(properties = "grpc.server.port=0")
@Import(TestcontainersConfiguration.class)
class OutboxTraceContextIntegrationTest {

    @Autowired private OutboxWriter outboxWriter;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private KafkaContainer kafkaContainer;

    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build();
    private final Tracer tracer = tracerProvider.get("test");
    private final String topic = "trace-test-" + UUID.randomUUID();

    @AfterEach
    void close() {
        tracerProvider.close();
    }

    private UUID write() {
        UUID eventId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(tx -> outboxWriter.append(eventId, "payment", UUID.randomUUID(),
                "payment.test", topic, "key-1", "{}".getBytes(StandardCharsets.UTF_8)));
        return eventId;
    }

    private ConsumerRecord<String, byte[]> published(UUID eventId) {
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "trace-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(topic));
            long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < end) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(500))) {
                    if (eventId.toString().equals(header(record, "event_id"))) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("event " + eventId + " not published");
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @Test
    void theRequestsTraceGoesWithTheEvent() {
        Span request = tracer.spanBuilder("POST /payments").startSpan();
        UUID eventId;
        try (Scope ignored = request.makeCurrent()) {
            eventId = write();
        } finally {
            request.end();
        }
        String expected = "00-" + request.getSpanContext().getTraceId() + "-" + request.getSpanContext().getSpanId() + "-01";

        assertThat(jdbc.queryForObject("SELECT trace_parent FROM outbox_event WHERE id = ?", String.class, eventId))
                .isEqualTo(expected);
        assertThat(header(published(eventId), "traceparent")).isEqualTo(expected);   // relay: another thread, later
    }

    @Test
    void withoutTracingNothingIsStoredOrSent() {
        UUID eventId = write();                                                       // no current span

        assertThat(jdbc.queryForObject("SELECT trace_parent FROM outbox_event WHERE id = ?", String.class, eventId)).isNull();
        ConsumerRecord<String, byte[]> record = published(eventId);
        assertThat(record.headers().lastHeader("traceparent")).isNull();
        assertThat(header(record, "event_type")).isEqualTo("payment.test");
    }

    @Test
    void underTheUnsampledPollContextNothingIsRecorded() {
        Span poll;
        try (Scope ignored = TraceContext.unsampled().makeCurrent()) {
            poll = tracer.spanBuilder("SELECT outbox_event").startSpan();           // e.g. the agent's JDBC span
            poll.end();
        }
        assertThat(poll.isRecording()).isFalse();
        assertThat(poll.getSpanContext().isSampled()).isFalse();
        assertThat(Span.fromContext(TraceContext.unsampled()).getSpanContext().isValid()).isTrue();
    }

    @Test
    void aStoredContextBecomesTheParentAgain() {
        Span request = tracer.spanBuilder("request").startSpan();
        String traceparent = "00-" + request.getSpanContext().getTraceId() + "-" + request.getSpanContext().getSpanId() + "-01";
        request.end();

        Span child = tracer.spanBuilder("publish").setParent(TraceContext.parent(traceparent, null)).startSpan();

        assertThat(child.getSpanContext().getTraceId()).isEqualTo(request.getSpanContext().getTraceId());
        assertThat(Span.fromContext(TraceContext.parent(null, null)).getSpanContext().isValid()).isFalse();
    }
}
