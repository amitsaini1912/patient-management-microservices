package org.pm.simulator.network;

/**
 * ISO 8583 field 39 (response code), the few we simulate. {@code declineReason} is what the acquirer tells the
 * merchant (Stripe-style words, so billing doesn't have to know ISO codes). {@code retryable}: may the merchant
 * try again with a NEW attempt? Only 91 - the issuer never saw or never answered the request; nothing says
 * the card is bad. (Real scheme retry rules are finer; e.g. Visa allows limited retries on 51 too.)
 */
public enum ResponseCode {
    APPROVED("00", null, false),
    DO_NOT_HONOR("05", "card_declined", false),
    INSUFFICIENT_FUNDS("51", "insufficient_funds", false),
    EXPIRED_CARD("54", "expired_card", false),
    ISSUER_UNAVAILABLE("91", "issuer_unavailable", true);

    private final String code;
    private final String declineReason;
    private final boolean retryable;

    ResponseCode(String code, String declineReason, boolean retryable) {
        this.code = code;
        this.declineReason = declineReason;
        this.retryable = retryable;
    }

    public String code() { return code; }
    public String declineReason() { return declineReason; }
    public boolean retryable() { return retryable; }
    public boolean approved() { return this == APPROVED; }
}
