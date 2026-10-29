package org.pm.billingservice.config;

import java.time.Clock;
import org.pm.billingservice.payment.PaymentProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(PaymentProperties.class)
public class BillingConfig {

    /** Injected instead of calling Instant.now(), so time-based code can be tested with a fixed clock. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
