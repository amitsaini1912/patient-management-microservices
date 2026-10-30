package org.pm.billingservice.card;

/** card-vault didn't answer (down, timeout, 5xx). Nothing was charged; the client may retry (503). */
public class CardVaultUnavailableException extends RuntimeException {

    public CardVaultUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
