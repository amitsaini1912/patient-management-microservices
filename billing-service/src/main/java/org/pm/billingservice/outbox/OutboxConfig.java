package org.pm.billingservice.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfig {

    /**
     * Created at startup if missing (KafkaAdmin). Fine for local/compose; in production topics are usually
     * owned by infrastructure code with replication factor 3.
     */
    @Bean
    NewTopic paymentEventsTopic(@Value("${billing.events.payment-topic}") String topic,
                                @Value("${billing.events.partitions:3}") int partitions,
                                @Value("${billing.events.replicas:1}") short replicas) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(replicas).build();
    }
}
