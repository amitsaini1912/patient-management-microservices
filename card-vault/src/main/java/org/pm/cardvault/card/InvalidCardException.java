package org.pm.cardvault.card;

/**
 * The card can't be tokenized. The message is shown to the client and logged, so it must NEVER contain the
 * number (or any part of it): "card number fails the check digit", never "4111... fails".
 */
public class InvalidCardException extends RuntimeException {

    public InvalidCardException(String message) {
        super(message);
    }
}
