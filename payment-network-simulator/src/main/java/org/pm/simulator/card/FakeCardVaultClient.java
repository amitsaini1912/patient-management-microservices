package org.pm.simulator.card;

import java.time.YearMonth;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.pm.simulator.issuer.Issuer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * simulator.card-vault.mode=fake (tests, and runs without card-vault): Stripe-style test tokens stand for the
 * issuer's test cards. Any other token is the Visa test card; "tok_unknown..." is a token the vault doesn't know.
 */
@Component
@ConditionalOnProperty(name = "simulator.card-vault.mode", havingValue = "fake")
public class FakeCardVaultClient implements CardVaultClient {

    private static final YearMonth VALID = YearMonth.of(2099, 12);

    private final Map<String, Card> cards = new ConcurrentHashMap<>(Map.of(
            "pm_card_visa", new Card(Issuer.VISA, VALID),
            "pm_card_mastercard", new Card(Issuer.MASTERCARD, VALID),
            "pm_card_declined", new Card(Issuer.DO_NOT_HONOR, VALID),
            "pm_card_insufficient_funds", new Card(Issuer.INSUFFICIENT_FUNDS, VALID),
            "pm_card_expired", new Card(Issuer.EXPIRED, VALID),
            "pm_card_issuer_unavailable", new Card(Issuer.UNAVAILABLE, VALID),
            "pm_card_slow", new Card(Issuer.SLOW, VALID)));
    private volatile boolean down;

    @Override
    public Card detokenize(String token) {
        if (down) {
            throw new CardVaultUnavailableException("fake card vault is down", null);
        }
        if (token.startsWith("tok_unknown")) {
            throw new CardNotFoundException("card-vault doesn't know this token");
        }
        return cards.getOrDefault(token, new Card(Issuer.VISA, VALID));
    }

    /** For tests: this token stands for this card. */
    public void register(String token, String pan, YearMonth expiry) {
        cards.put(token, new Card(pan, expiry));
    }

    /** For tests: the vault stops answering (true) or comes back (false). */
    public void setDown(boolean down) {
        this.down = down;
    }
}
