package org.pm.analyticservice.kafka;

import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * What happens when a listener throws (for every @KafkaListener here; Boot wires this bean into its container factory):
 *   - bad data (InvalidEventException): straight to {@code <topic>.DLT}, no retries
 *   - anything else (DB down ...): retried {@code retry-attempts} times, then to {@code <topic>.DLT}
 * Either way the offset moves on, so one poison message can't stop the partition forever.
 * The DLT record is the original bytes plus headers saying which topic/partition/offset it came from and why it failed.
 */
@Configuration
public class KafkaErrorHandlingConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlingConfig.class);
    static final String DLT_SUFFIX = ".DLT";

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<?, ?> kafkaTemplate,
                                          @Value("${analytics.kafka.retry-attempts}") long retryAttempts,
                                          @Value("${analytics.kafka.retry-interval}") Duration retryInterval) {
        // partition -1: let Kafka pick, so the DLT needn't have as many partitions as the source topic
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, e) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
        DefaultErrorHandler handler = new DefaultErrorHandler((record, e) -> {
            log.error("sending {}-{}@{} to {}{}: {}", record.topic(), record.partition(), record.offset(),
                    record.topic(), DLT_SUFFIX, e.getMessage());
            recoverer.accept(record, e);
        }, new FixedBackOff(retryInterval.toMillis(), retryAttempts));
        handler.addNotRetryableExceptions(InvalidEventException.class);
        return handler;
    }

    @Bean
    NewTopic paymentEventsDlt(@Value("${analytics.kafka.payment-topic}") String topic) {
        return TopicBuilder.name(topic + DLT_SUFFIX).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic patientDlt(@Value("${analytics.kafka.patient-topic}") String topic) {
        return TopicBuilder.name(topic + DLT_SUFFIX).partitions(1).replicas(1).build();
    }
}
