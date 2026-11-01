package org.pm.billingservice.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
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
 *
 * Secret rotation (ADR 0029): up to two secrets are accepted - the current one and the previous one - and the header
 * may carry several v1= signatures (a sender signing with old and new during its switch). Rotate: 1. billing gets
 * secret=new, previous=old; 2. the acquirer switches to new; 3. when billing_webhooks_signature_total{key="previous"}
 * stops growing (retries drained), drop previous. No moment where valid webhooks are refused.
 */
public class WebhookSignatureVerifier {

    /** Which of our secrets the signature was made with. */
    public enum Key { CURRENT, PREVIOUS }

    private final List<byte[]> secrets;   // [current] or [current, previous]
    private final Duration tolerance;
    private final Clock clock;

    public WebhookSignatureVerifier(String secret, Duration tolerance, Clock clock) {
        this(secret, null, tolerance, clock);
    }

    public WebhookSignatureVerifier(String secret, String previousSecret, Duration tolerance, Clock clock) {
        if (secret == null || secret.isBlank()) {
            // fail closed: without a secret we could only accept everything or nothing
            throw new IllegalStateException("billing.webhooks.gateway.secret must be set (env GATEWAY_WEBHOOK_SECRET)");
        }
        List<byte[]> keys = new ArrayList<>();
        keys.add(secret.getBytes(StandardCharsets.UTF_8));
        if (previousSecret != null && !previousSecret.isBlank()) {
            keys.add(previousSecret.getBytes(StandardCharsets.UTF_8));
        }
        this.secrets = List.copyOf(keys);
        this.tolerance = tolerance;
        this.clock = clock;
    }

    /**
     * Throws {@link InvalidSignatureException} unless the header proves the body came from the acquirer, recently.
     * Returns which secret proved it.
     */
    public Key verify(String header, byte[] body) {
        if (header == null || header.isBlank()) {
            throw new InvalidSignatureException("missing signature header");
        }
        String timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals("t")) {
                timestamp = kv[1];
            } else if (kv.length == 2 && kv[0].equals("v1")) {
                signatures.add(kv[1]);
            }
        }
        if (timestamp == null || signatures.isEmpty() || signatures.size() > 4) {
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
        // every (secret, signature) pair is compared - no early exit on the first match, so timing doesn't say which
        Key matched = null;
        for (int i = 0; i < secrets.size(); i++) {
            byte[] expected = HexFormat.of().formatHex(hmac(secrets.get(i), timestamp, body)).getBytes(StandardCharsets.US_ASCII);
            for (String signature : signatures) {
                if (MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII)) && matched == null) {
                    matched = i == 0 ? Key.CURRENT : Key.PREVIOUS;
                }
            }
        }
        if (matched == null) {
            throw new InvalidSignatureException("signature mismatch");
        }
        return matched;
    }

    private static byte[] hmac(byte[] secret, String timestamp, byte[] body) {
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
