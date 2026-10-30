package org.pm.billingservice.card;

import java.util.Optional;

/**
 * billing's only view of card-vault: read a token's metadata (permission READ_METADATA). billing has no way to get
 * a card number back - its API key can't detokenize (ADR 0013).
 */
public interface CardVaultClient {

    /** Empty if the vault doesn't know the token. Throws CardVaultUnavailableException if the vault can't answer. */
    Optional<CardInfo> metadata(String token);
}
