package org.pm.billingservice.webhook;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GatewayWebhookProperties.class)
public class WebhookConfig {

    @Bean
    WebhookSignatureVerifier webhookSignatureVerifier(GatewayWebhookProperties properties, Clock clock) {
        return new WebhookSignatureVerifier(properties.secret(), properties.tolerance(), clock);
    }
}
