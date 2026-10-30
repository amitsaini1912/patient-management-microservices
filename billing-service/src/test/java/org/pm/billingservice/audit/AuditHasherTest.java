package org.pm.billingservice.audit;

import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditHasherTest {

    private static final String KEY = Base64.getEncoder().encodeToString("k".repeat(32).getBytes());
    private static final Instant AT = Instant.parse("2026-10-30T10:15:30.123456789Z");

    private final AuditHasher hasher = new AuditHasher(KEY);

    @Test
    void movingTextBetweenFieldsChangesTheHash() {
        // with a plain "a|b" join both rows would be "x|y|z" - one hash for two different rows
        String a = hasher.hash(AuditHasher.GENESIS, 1, AT, "x|y", "z", "payment", "p1", "{}");
        String b = hasher.hash(AuditHasher.GENESIS, 1, AT, "x", "y|z", "payment", "p1", "{}");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void timeIsHashedAtMicrosecondsLikePostgresStoresIt() {
        String nanos = hasher.hash(AuditHasher.GENESIS, 1, AT, "a", "b", "c", "d", "{}");
        String micros = hasher.hash(AuditHasher.GENESIS, 1, Instant.parse("2026-10-30T10:15:30.123456Z"), "a", "b", "c", "d", "{}");
        assertThat(nanos).isEqualTo(micros).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void anotherKeyGivesAnotherHashAndShortKeysAreRefused() {
        String other = new AuditHasher(Base64.getEncoder().encodeToString("j".repeat(32).getBytes()))
                .hash(AuditHasher.GENESIS, 1, AT, "a", "b", "c", "d", "{}");
        assertThat(other).isNotEqualTo(hasher.hash(AuditHasher.GENESIS, 1, AT, "a", "b", "c", "d", "{}"));
        assertThatThrownBy(() -> new AuditHasher(Base64.getEncoder().encodeToString(new byte[16])))
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new AuditHasher("")).hasMessageContaining("BILLING_AUDIT_KEY");
    }
}
