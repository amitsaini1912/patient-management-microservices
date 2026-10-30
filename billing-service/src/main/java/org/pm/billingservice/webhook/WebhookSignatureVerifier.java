package org.pm.billingservice.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Checks {@code Sim-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, "<t>.<raw body>")>}.
 *
 *   - HMAC over the RAW bytes we received (not re-serialized JSON: one changed space = different MAC)
 *   - constant-time compare (MessageDigest.isEqual): a byte-by-byte compare that stops early leaks, through
 *     response timing, how many leading bytes of a forged signature were right
 *   - timestamp within the tolerance, so a captured request can't be replayed hours later
 *     (and the timestamp is inside the MAC, so it can't be swapped for a fresh one)
 */
public class WebhookSignatureVerifier {

    private final byte[] secret;
    private final Duration tolerance;
    private final Clock clock;

    public WebhookSignatureVerifier(String secret, Duration tolerance, Clock clock) {
        if (secret == null || secret.isBlank()) {
            // fail closed: without a secret we could only accept everything or nothing
            throw new IllegalStateException("billing.webhooks.gateway.secret must be set (env GATEWAY_WEBHOOK_SECRET)");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.tolerance = tolerance;
        this.clock = clock;
    }

    /** Throws {@link InvalidSignatureException} unless the header proves the body came from the acquirer, recently. */
    public void verify(String header, byte[] body) {
        if (header == null || header.isBlank()) {
            throw new InvalidSignatureException("missing signature header");
        }
        String timestamp = null;
        String signature = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals("t")) {
                timestamp = kv[1];
            } else if (kv.length == 2 && kv[0].equals("v1")) {
                signature = kv[1];
            }
        }
        if (timestamp == null || signature == null) {
            throw new InvalidSignatureException("malformed signature header");
        }
        long seconds;
        try {
            seconds = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new InvalidSignatureException("malformed timestamp");
        }
        Duration age = Duration.between(Instant.ofEpochSecond(seconds), clock.instant()).abs();
        if (age.compareTo(tolerance) > 0) {
            throw new InvalidSignatureException("timestamp outside tolerance");
        }
        byte[] expected = HexFormat.of().formatHex(hmac(timestamp, body)).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII))) {
            throw new InvalidSignatureException("signature mismatch");
        }
    }

    private byte[] hmac(String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is always available", e);
        }
    }

    public static class InvalidSignatureException extends RuntimeException {
        InvalidSignatureException(String message) {
            super(message);
        }
    }
}
