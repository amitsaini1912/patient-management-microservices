package org.pm.cardvault.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-256-GCM. GCM = encryption + integrity: a changed ciphertext, IV, key or associated data makes decryption
 * FAIL (bad tag) instead of returning garbage.
 *
 *   - new random 12-byte IV per encryption: reusing an IV with the same key breaks GCM completely
 *   - 128-bit tag
 *   - associated data (AAD) = the card's token: authenticated but not encrypted. A ciphertext moved onto
 *     another row (another token) fails to decrypt.
 */
public class CardCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final KeyRing keys;
    private final SecureRandom random = new SecureRandom();

    public CardCipher(KeyRing keys) {
        this.keys = keys;
    }

    public record Encrypted(byte[] ciphertext, byte[] iv, int keyVersion) {}

    public Encrypted encrypt(String plaintext, String associatedData) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keys.active(), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return new Encrypted(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)), iv, keys.activeVersion());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    public String decrypt(Encrypted encrypted, String associatedData) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keys.version(encrypted.keyVersion()), new GCMParameterSpec(TAG_BITS, encrypted.iv()));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(encrypted.ciphertext()), StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new TamperedCiphertextException();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decryption failed", e);
        }
    }

    /** The data, IV, key version or associated data doesn't match what was encrypted. Never "best effort". */
    public static class TamperedCiphertextException extends RuntimeException {
        TamperedCiphertextException() {
            super("ciphertext failed authentication (GCM tag mismatch)");
        }
    }
}
