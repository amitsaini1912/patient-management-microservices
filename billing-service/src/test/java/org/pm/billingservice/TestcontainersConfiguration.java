package org.pm.billingservice;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * One real Postgres, one real Kafka (KRaft) and one real Redis (Day 12, card rate limit) for Spring tests.
 * Kafka 3.9.1, not k8s' 3.9.0: with 3.9.0 the broker refuses to start under Testcontainers
 * ("advertised.listeners cannot use the nonroutable meta-address 0.0.0.0"); fixed in 3.9.1.
 * @ServiceConnection points the datasource / Kafka client at them automatically.
 * Import it with @Import(TestcontainersConfiguration.class).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:16");
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer("apache/kafka:3.9.1");
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    }
}
