package org.pm.billingservice.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyConfig {

    /** Registered here (not @Component) so it only runs on the payment API, not on actuator etc. */
    @Bean
    FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(IdempotencyStore store, IdempotencyProperties properties,
                                                                Clock clock, ObjectMapper objectMapper) {
        FilterRegistrationBean<IdempotencyFilter> registration =
                new FilterRegistrationBean<>(new IdempotencyFilter(store, properties, clock, objectMapper));
        registration.addUrlPatterns("/payments/*"); // also matches "/payments" itself
        return registration;
    }
}
