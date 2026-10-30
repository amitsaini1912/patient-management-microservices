package org.pm.simulator.settlement;

import java.time.Instant;

/**
 * One money movement the acquirer will settle: a capture (we owe the merchant) or a refund (the merchant owes us).
 * {@code merchantReference} is the merchant's payment id (from the authorization); for refunds, {@code id} is the
 * refund id the merchant got back from POST /refunds.
 */
public record SettlementRecord(String id, Type type, String authorizationId, String merchantReference,
                               long amountMinor, String currency, Instant occurredAt) {

    public enum Type { CAPTURE, REFUND }

    SettlementRecord withAmount(long amount, String newCurrency) {
        return new SettlementRecord(id, type, authorizationId, merchantReference, amount, newCurrency, occurredAt);
    }
}
