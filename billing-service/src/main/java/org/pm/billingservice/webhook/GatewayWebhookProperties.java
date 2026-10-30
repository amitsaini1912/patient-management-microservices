package org.pm.billingservice.webhook;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * billing.webhooks.gateway.*
 *
 * @param secret    shared with the acquirer; from env GATEWAY_WEBHOOK_SECRET, never in code
 * @param tolerance how far the signed timestamp may be from our clock (both ways) before we refuse: limits replays
 */
@ConfigurationProperties("billing.webhooks.gateway")
public record GatewayWebhookProperties(String secret, @DefaultValue("5m") Duration tolerance) {
}
