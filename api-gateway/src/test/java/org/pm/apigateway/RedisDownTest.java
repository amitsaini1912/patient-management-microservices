package org.pm.apigateway;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis dies while the gateway is running: the limiter fails OPEN, and FAST. The realistic case is a connection
 * that was working and then breaks (Lettuce queues commands on it until the command timeout - 60s by default; the
 * gateway sets 200ms). A broken limiter must not take payments down with it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "GATEWAY_PAYMENTS_RATE_PER_SECOND=1", "GATEWAY_PAYMENTS_BURST=1"})
@AutoConfigureWebTestClient
class RedisDownTest extends GatewayTestSupport {

    static final GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    static {
        redis.start();
    }

    @DynamicPropertySource
    static void redisAddress(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired private WebTestClient web;

    @Test
    void whenRedisDiesRequestsStillGoThroughQuicklyEvenAboveTheLimit() {
        String token = "Bearer valid-carol@test.com";
        web.get().uri("/api/payments/1").header("Authorization", token).exchange().expectStatus().isOk(); // connected

        redis.stop();

        for (int i = 0; i < 3; i++) { // bucket of 1: with Redis these would all be 429
            Instant start = Instant.now();
            web.get().uri("/api/payments/1").header("Authorization", token).exchange().expectStatus().isOk();
            assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(2));
        }
    }
}
