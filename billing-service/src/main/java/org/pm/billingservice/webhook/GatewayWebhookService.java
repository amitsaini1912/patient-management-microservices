package org.pm.billingservice.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.pm.billingservice.payment.GatewayEvent;
import org.pm.billingservice.payment.GatewayEventOutcome;
import org.pm.billingservice.payment.PaymentService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One transaction per webhook: remember the event id, apply it, store the outcome. The row and the payment change
 * commit together; if applying fails, both roll back and the acquirer's retry is processed from scratch.
 */
@Service
public class GatewayWebhookService {

    private final JdbcTemplate jdbc;
    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public GatewayWebhookService(JdbcTemplate jdbc, PaymentService paymentService, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.paymentService = paymentService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** {@code body} must already be signature-verified. */
    @Transactional
    public GatewayEventOutcome handle(byte[] body) {
        GatewayEvent event = parse(body);
        // PK insert first: a concurrent delivery of the same event waits on this row, then finds it -> DUPLICATE
        boolean first = jdbc.update("""
                INSERT INTO webhook_event (id, type, payment_id, outcome, payload, received_at)
                VALUES (?, ?, ?, 'PROCESSING', ?, ?)
                ON CONFLICT (id) DO NOTHING""",
                event.eventId(), event.type(), event.paymentId(), new String(body, StandardCharsets.UTF_8),
                Timestamp.from(clock.instant())) == 1;
        if (!first) {
            return GatewayEventOutcome.DUPLICATE;
        }
        GatewayEventOutcome outcome = event.paymentId() == null
                ? GatewayEventOutcome.IGNORED_UNKNOWN_PAYMENT
                : paymentService.applyGatewayEvent(event);
        jdbc.update("UPDATE webhook_event SET outcome = ? WHERE id = ?", outcome.name(), event.eventId());
        return outcome;
    }

    private GatewayEvent parse(byte[] body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            throw new IllegalArgumentException("webhook body is not JSON");
        }
        String id = text(root, "id");
        String type = text(root, "type");
        JsonNode data = root.path("data");
        if (id == null || type == null || !data.isObject()) {
            throw new IllegalArgumentException("webhook needs id, type and data");
        }
        return new GatewayEvent(id, type, uuidOrNull(text(data, "merchant_reference")), text(data, "authorization_id"),
                text(data, "currency"), data.path("captured_minor").asLong(0), text(data, "decline_reason"),
                text(data, "network_response_code"), data.path("retryable").asBoolean(false));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static UUID uuidOrNull(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
