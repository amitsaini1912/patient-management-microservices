package org.pm.cardvault.card;

public class CardNotFoundException extends RuntimeException {

    public CardNotFoundException(String token) {
        super("no card for token " + token);
    }
}
