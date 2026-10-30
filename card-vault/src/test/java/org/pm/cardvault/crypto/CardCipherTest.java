package org.pm.cardvault.crypto;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardCipherTest {

    private static final String K1 = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String K2 = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    private static final String PAN = "4111111111111111";

    private final CardCipher v1 = new CardCipher(KeyRing.parse("1:" + K1, 1));

    @Test
    void roundTrip() {
        CardCipher.Encrypted e = v1.encrypt(PAN, "tok_a");

        assertThat(v1.decrypt(e, "tok_a")).isEqualTo(PAN);
        assertThat(e.iv()).hasSize(12);
        assertThat(e.ciphertext()).hasSize(PAN.length() + 16); // + 128-bit GCM tag
        assertThat(new String(e.ciphertext())).doesNotContain(PAN);
    }

    @Test
    void freshIvEveryTimeSoTheSameCardNeverEncryptsTheSame() {
        Set<String> ivs = new HashSet<>();
        Set<String> ciphertexts = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            CardCipher.Encrypted e = v1.encrypt(PAN, "tok_a");
            ivs.add(Base64.getEncoder().encodeToString(e.iv()));
            ciphertexts.add(Base64.getEncoder().encodeToString(e.ciphertext()));
        }
        assertThat(ivs).hasSize(1000);
        assertThat(ciphertexts).hasSize(1000);
    }

    @Test
    void anyTamperingFailsTheTagInsteadOfReturningGarbage() {
        CardCipher.Encrypted e = v1.encrypt(PAN, "tok_a");

        byte[] flippedData = e.ciphertext().clone();
        flippedData[0] ^= 1;
        byte[] flippedTag = e.ciphertext().clone();
        flippedTag[flippedTag.length - 1] ^= 1;
        byte[] otherIv = e.iv().clone();
        otherIv[0] ^= 1;

        assertTampered(() -> v1.decrypt(new CardCipher.Encrypted(flippedData, e.iv(), 1), "tok_a"));
        assertTampered(() -> v1.decrypt(new CardCipher.Encrypted(flippedTag, e.iv(), 1), "tok_a"));
        assertTampered(() -> v1.decrypt(new CardCipher.Encrypted(e.ciphertext(), otherIv, 1), "tok_a"));
        // the right ciphertext on the wrong row: the token is the associated data
        assertTampered(() -> v1.decrypt(e, "tok_b"));
    }

    @Test
    void afterRotationOldCardsStillDecryptAndNewOnesUseTheNewKey() {
        CardCipher.Encrypted old = v1.encrypt(PAN, "tok_old");
        CardCipher rotated = new CardCipher(KeyRing.parse("1:" + K1 + ",2:" + K2, 2));

        CardCipher.Encrypted fresh = rotated.encrypt(PAN, "tok_new");

        assertThat(old.keyVersion()).isEqualTo(1);
        assertThat(fresh.keyVersion()).isEqualTo(2);
        assertThat(rotated.decrypt(old, "tok_old")).isEqualTo(PAN);   // read with its own version
        assertThat(rotated.decrypt(fresh, "tok_new")).isEqualTo(PAN);
        // decrypting with the wrong key version is also a failed tag, not garbage
        assertTampered(() -> rotated.decrypt(new CardCipher.Encrypted(old.ciphertext(), old.iv(), 2), "tok_old"));
    }

    @Test
    void misconfiguredKeysFailAtStartupWithoutPrintingThem() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> KeyRing.parse("1:" + shortKey, 1)).hasMessageContaining("AES-256 needs 32");
        assertThatThrownBy(() -> KeyRing.parse("1:" + K1, 2)).hasMessageContaining("active key version 2");
        assertThatThrownBy(() -> KeyRing.parse(" ", 1)).hasMessageContaining("VAULT_ENCRYPTION_KEYS");
        assertThatThrownBy(() -> KeyRing.parse("garbage", 1)).hasMessageNotContaining("garbage");
        assertThat(KeyRing.parse("1:" + K1, 1).toString()).doesNotContain(K1);
    }

    private static void assertTampered(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(CardCipher.TamperedCiphertextException.class);
    }
}
