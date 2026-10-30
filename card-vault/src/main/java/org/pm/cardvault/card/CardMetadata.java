package org.pm.cardvault.card;

import java.time.Instant;

/** Everything about a card except the number. Safe to give to billing, logs, events. */
public record CardMetadata(String token, String last4, CardBrand brand, String fingerprint, int expiryMonth,
                           int expiryYear, Instant createdAt) {
}
