package org.pm.simulator.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stripe-style signature header: {@code Sim-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256>}, where the HMAC is
 * over {@code "<t>.<raw body>"}. Signing the timestamp too means an attacker can't replay an old body with a fresh
 * timestamp; the receiver rejects timestamps older than its tolerance.
 */
public final class WebhookSigner {

    public static final String HEADER = "Sim-Signature";

    private WebhookSigner() {}

    public static String header(String secret, long timestamp, String body) {
        return "t=" + timestamp + ",v1=" + hmacHex(secret, timestamp + "." + body);
    }

    public static String hmacHex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is always available", e);
        }
    }
}
