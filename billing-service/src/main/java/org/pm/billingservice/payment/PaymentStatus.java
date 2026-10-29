package org.pm.billingservice.payment;

/**
 * Where a payment is in its life. The allowed moves between these are in {@link PaymentStateMachine}.
 * A partial capture is not a status: it is CAPTURED with captured amount < authorized amount.
 */
public enum PaymentStatus {
    /** Recorded, nothing sent to the card network yet. */
    CREATED,
    /** Held by the risk engine for a manual decision before authorizing. */
    PENDING_REVIEW,
    /** The issuer reserved the money on the card. Nothing has moved yet. */
    AUTHORIZED,
    /** Money taken from the reserved amount (all or part of it). */
    CAPTURED,
    /** Some of the captured money was given back. */
    PARTIALLY_REFUNDED,
    /** All captured money was given back. */
    REFUNDED,
    /** Declined (by the issuer, the risk engine or the network). */
    FAILED,
    /** The authorization was cancelled before capture; the hold is released. */
    VOIDED,
    /** Not captured in time; the issuer released the hold (cards: ~7 days). */
    EXPIRED;

    public boolean isTerminal() {
        return PaymentStateMachine.allowedFrom(this).isEmpty();
    }
}
