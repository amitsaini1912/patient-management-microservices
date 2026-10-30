package org.pm.billingservice.processor;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * billing.simulator.* - how we reach payment-network-simulator (our "acquirer").
 *
 * @param readTimeout keep it short: a timeout is NOT a decline (we don't know what happened), the client gets a
 *                    502 and retries with the same Idempotency-Key; the simulator replays its first answer
 */
@ConfigurationProperties("billing.simulator")
public record SimulatorProperties(
        URI baseUrl,
        String apiKey,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout) {
}
