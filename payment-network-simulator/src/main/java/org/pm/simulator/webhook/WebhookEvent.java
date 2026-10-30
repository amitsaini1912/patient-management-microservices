package org.pm.simulator.webhook;

import java.util.Map;

/**
 * What we POST to the merchant. {@code id} is unique per event (the merchant dedups on it); a retry or a
 * deliberate duplicate re-sends the SAME id. {@code data} is a snapshot of the authorization after the change.
 */
public record WebhookEvent(String id, String type, long created, Map<String, Object> data) {
}
