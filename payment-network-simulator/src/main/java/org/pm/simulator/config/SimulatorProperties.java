package org.pm.simulator.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * simulator.* settings.
 *
 * @param apiKey   what a merchant (billing-service) must send as {@code Authorization: Bearer <apiKey>}
 * @param webhooks where and how we deliver webhooks
 */
@ConfigurationProperties("simulator")
public record SimulatorProperties(String apiKey, Webhooks webhooks) {

    /**
     * @param targetUrl      the merchant's webhook endpoint
     * @param secret         shared HMAC secret (the merchant verifies our signature with it)
     * @param delay          wait before the first attempt (webhooks are asynchronous, like at a real PSP)
     * @param maxAttempts    attempts per event before we give up
     * @param initialBackoff wait before the 2nd attempt; doubles each time
     */
    public record Webhooks(URI targetUrl,
                           String secret,
                           @DefaultValue("500ms") Duration delay,
                           @DefaultValue("5") int maxAttempts,
                           @DefaultValue("1s") Duration initialBackoff) {
    }
}
