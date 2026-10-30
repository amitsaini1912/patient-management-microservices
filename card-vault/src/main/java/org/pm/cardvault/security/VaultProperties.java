package org.pm.cardvault.security;

import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * vault.* settings. Every secret comes from env (see ../.env.example).
 *
 * @param encryptionKeys       "1:base64,2:base64" (AES-256 keys by version)
 * @param activeKeyVersion     the version new cards are encrypted with
 * @param fingerprintKey       base64, >= 32 bytes, for HMAC fingerprints (never rotated)
 * @param clients              caller name -> its API key and what it may do
 */
@ConfigurationProperties("vault")
public record VaultProperties(String encryptionKeys, int activeKeyVersion, String fingerprintKey,
                              Map<String, Client> clients) {

    public record Client(String apiKey, Set<Permission> permissions) {
        @Override
        public String toString() {
            return "Client{permissions=" + permissions + "}"; // never the key
        }
    }

    @Override
    public String toString() {
        return "VaultProperties{activeKeyVersion=" + activeKeyVersion + ", clients=" + clients + "}";
    }
}
