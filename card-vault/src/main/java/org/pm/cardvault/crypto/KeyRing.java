package org.pm.cardvault.crypto;

import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * All AES-256 data keys by version, and which one encrypts new cards.
 *
 * Configured as {@code "1:<base64 32 bytes>,2:<base64 32 bytes>"} plus an active version (env, never in code).
 * Rotation = add a version, make it active, keep the old ones as long as rows use them (ADR 0013).
 * In production these would be data keys wrapped by a KMS/HSM key; env vars are this project's stand-in.
 */
public final class KeyRing {

    private final Map<Integer, SecretKey> keys;
    private final int activeVersion;

    public KeyRing(Map<Integer, SecretKey> keys, int activeVersion) {
        if (!keys.containsKey(activeVersion)) {
            throw new IllegalStateException("active key version " + activeVersion + " is not in the key ring " + keys.keySet());
        }
        this.keys = Collections.unmodifiableMap(new TreeMap<>(keys));
        this.activeVersion = activeVersion;
    }

    /** Parses {@code "1:base64,2:base64"}. Every key must decode to exactly 32 bytes (AES-256). */
    public static KeyRing parse(String spec, int activeVersion) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalStateException("no encryption keys configured (env VAULT_ENCRYPTION_KEYS)");
        }
        Map<Integer, SecretKey> keys = new TreeMap<>();
        for (String entry : spec.split(",")) {
            String[] parts = entry.trim().split(":", 2);
            if (parts.length != 2) {
                throw new IllegalStateException("key entry must look like <version>:<base64>"); // never echo the entry
            }
            int version = Integer.parseInt(parts[0].trim());
            byte[] raw = Base64.getDecoder().decode(parts[1].trim());
            if (raw.length != 32) {
                throw new IllegalStateException("key version " + version + " is " + raw.length + " bytes; AES-256 needs 32");
            }
            if (keys.put(version, new SecretKeySpec(raw, "AES")) != null) {
                throw new IllegalStateException("key version " + version + " is configured twice");
            }
        }
        return new KeyRing(keys, activeVersion);
    }

    public int activeVersion() {
        return activeVersion;
    }

    SecretKey active() {
        return keys.get(activeVersion);
    }

    SecretKey version(int version) {
        SecretKey key = keys.get(version);
        if (key == null) {
            // a row encrypted with a key that was removed from the ring: unreadable until the key is restored
            throw new IllegalStateException("key version " + version + " is not in the key ring");
        }
        return key;
    }

    @Override
    public String toString() {
        return "KeyRing{versions=" + keys.keySet() + ", active=" + activeVersion + "}"; // never the key bytes
    }
}
