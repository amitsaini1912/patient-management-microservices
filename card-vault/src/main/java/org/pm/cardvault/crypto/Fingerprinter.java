package org.pm.cardvault.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * fingerprint = hex(HMAC-SHA256(fingerprint key, PAN)). Same card -> same fingerprint, so billing/risk can spot
 * "this card was used before" without ever seeing the number.
 *
 * Why HMAC and not plain SHA-256: there are only ~10^15 valid PANs for a given BIN; a plain hash can be reversed by
 * trying them all. With a secret key, the fingerprint is useless without the vault. The key is NOT rotated like
 * the encryption keys: a new key would give every card a new fingerprint and break duplicate detection.
 */
public class Fingerprinter {

    private final byte[] key;

    public Fingerprinter(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("no fingerprint key configured (env VAULT_FINGERPRINT_KEY)");
        }
        this.key = Base64.getDecoder().decode(base64Key);
        if (key.length < 32) {
            throw new IllegalStateException("fingerprint key must be at least 32 bytes");
        }
    }

    public String fingerprint(String pan) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(pan.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is always available", e);
        }
    }
}
