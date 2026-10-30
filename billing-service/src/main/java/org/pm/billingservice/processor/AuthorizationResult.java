package org.pm.billingservice.processor;

/**
 * Outcome of an authorization: approved with a reference, or declined with a reason.
 * Day 11: the card network's response code (ISO 8583 field 39, e.g. "00", "51", "91") and whether a decline is
 * worth retrying (91 issuer unavailable: the card itself was never judged). Null code = the processor didn't say.
 */
public record AuthorizationResult(boolean approved, String processorReference, String declineReason,
                                  String networkResponseCode, boolean retryable) {

    public static AuthorizationResult approved(String processorReference) {
        return approved(processorReference, null);
    }

    public static AuthorizationResult approved(String processorReference, String networkResponseCode) {
        return new AuthorizationResult(true, processorReference, null, networkResponseCode, false);
    }

    public static AuthorizationResult declined(String declineReason) {
        return declined(declineReason, null, false);
    }

    public static AuthorizationResult declined(String declineReason, String networkResponseCode, boolean retryable) {
        return new AuthorizationResult(false, null, declineReason, networkResponseCode, retryable);
    }
}
