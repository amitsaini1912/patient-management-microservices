package org.pm.cardvault.security;

public enum Permission {
    /** send a PAN, get a token (api-gateway, on behalf of the patient app) */
    TOKENIZE,
    /** read last4 / brand / fingerprint / expiry (billing) */
    READ_METADATA,
    /** get the PAN back (only the payment network simulator, which plays the card network) */
    DETOKENIZE
}
