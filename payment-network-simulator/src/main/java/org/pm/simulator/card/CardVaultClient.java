package org.pm.simulator.card;

import java.time.YearMonth;
import org.pm.simulator.network.Iso8583;

/**
 * The acquirer's way to turn billing's card token back into a card number for the 0100 (card-vault DETOKENIZE,
 * ADR 0013). Only the simulator holds that permission: it plays the parties that legitimately see a PAN.
 */
public interface CardVaultClient {

    /** Throws {@link CardNotFoundException} for an unknown token, {@link CardVaultUnavailableException} otherwise. */
    Card detokenize(String token);

    record Card(String pan, YearMonth expiry) {
        @Override
        public String toString() {
            return "Card{pan=" + Iso8583.maskPan(pan) + ", expiry=" + expiry + "}";
        }
    }

    class CardNotFoundException extends RuntimeException {
        public CardNotFoundException(String message) {
            super(message);
        }
    }

    class CardVaultUnavailableException extends RuntimeException {
        public CardVaultUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
