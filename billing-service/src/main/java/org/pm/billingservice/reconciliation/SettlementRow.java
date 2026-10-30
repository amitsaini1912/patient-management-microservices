package org.pm.billingservice.reconciliation;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

/** One row of the acquirer's daily settlement report (payment-network-simulator GET /v1/settlements/{date}). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SettlementRow(String id, String type, String authorizationId, String merchantReference,
                            long amountMinor, String currency, Instant occurredAt) {
}
