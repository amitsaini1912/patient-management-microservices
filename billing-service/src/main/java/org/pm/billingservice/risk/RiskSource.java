package org.pm.billingservice.risk;

/** Who made the risk decision. */
public enum RiskSource {
    /** The risk engine answered (POST /v2/assess). */
    ENGINE,
    /** Billing's own rules: risk check off, engine down / slow / refusing, or the circuit is open. */
    FALLBACK
}
