package org.pm.apigateway;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;

/** A real Redis for the rate limiter; @ServiceConnection points spring.data.redis at it. */
@TestConfiguration(proxyBeanMethods = false)
class RedisTestcontainer {

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    }
}
