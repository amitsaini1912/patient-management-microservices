package org.pm.billingservice.card;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Tests only (billing.card-vault.mode=fake). Any token is a valid Visa ending 4242, expiring 12/2099, with a
 * fingerprint derived from the token - unless a test registered something specific, or the token starts with
 * "tok_unknown" (the vault doesn't know it).
 */
@Component
@ConditionalOnProperty(name = "billing.card-vault.mode", havingValue = "fake")
public class FakeCardVaultClient implements CardVaultClient {

    private final Map<String, CardInfo> cards = new ConcurrentHashMap<>();
    private volatile boolean down;

    @Override
    public Optional<CardInfo> metadata(String token) {
        if (down) {
            throw new CardVaultUnavailableException("fake card vault is down", null);
        }
        if (token.startsWith("tok_unknown")) {
            return Optional.empty();
        }
        return Optional.of(cards.getOrDefault(token, new CardInfo(token, "4242", "VISA", sha256(token), 12, 2099)));
    }

    /** For tests: the vault stops answering (true) or comes back (false). */
    public void setDown(boolean down) {
        this.down = down;
    }

    public void register(CardInfo card) {
        cards.put(card.token(), card);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
