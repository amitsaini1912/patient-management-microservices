package org.pm.billingservice.processor;

/** Outcome of an authorization: approved with a reference, or declined with a reason. */
public record AuthorizationResult(boolean approved, String processorReference, String declineReason) {

    public static AuthorizationResult approved(String processorReference) {
        return new AuthorizationResult(true, processorReference, null);
    }

    public static AuthorizationResult declined(String declineReason) {
        return new AuthorizationResult(false, null, declineReason);
    }
}
