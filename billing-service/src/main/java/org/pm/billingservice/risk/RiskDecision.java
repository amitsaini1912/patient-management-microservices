package org.pm.billingservice.risk;

/** What happens to a payment before it reaches the card network (ADR 0017). */
public enum RiskDecision {
    /** Authorize now. */
    ALLOW,
    /** PENDING_REVIEW: a person decides (POST /payments/{id}/review); nothing is sent to the network until then. */
    HOLD,
    /** FAILED with reason risk_declined; never sent to the network. */
    BLOCK
}
