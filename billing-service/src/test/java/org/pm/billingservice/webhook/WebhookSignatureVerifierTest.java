package org.pm.billingservice.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookSignatureVerifierTest {

    private static final String SECRET = "whsec_test";
    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private static final byte[] BODY = "{\"id\":\"evt_1\",\"type\":\"payment.captured\"}".getBytes(StandardCharsets.UTF_8);

    private final WebhookSignatureVerifier verifier =
            new WebhookSignatureVerifier(SECRET, Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void acceptsAFreshCorrectSignature() {
        assertThatCode(() -> verifier.verify(header(SECRET, NOW.getEpochSecond(), BODY), BODY)).doesNotThrowAnyException();
        // order of parts and whitespace don't matter
        long t = NOW.getEpochSecond();
        assertThatCode(() -> verifier.verify("v1=" + hmac(SECRET, t, BODY) + ", t=" + t, BODY)).doesNotThrowAnyException();
    }

    @Test
    void rejectsWrongSecretOrTamperedBody() {
        assertInvalid(header("other-secret", NOW.getEpochSecond(), BODY), BODY);
        byte[] tampered = "{\"id\":\"evt_1\",\"type\":\"payment.voided\"}".getBytes(StandardCharsets.UTF_8);
        assertInvalid(header(SECRET, NOW.getEpochSecond(), BODY), tampered);
        // re-serialised JSON with one extra space is a different body: we sign/verify RAW bytes
        assertInvalid(header(SECRET, NOW.getEpochSecond(), BODY), "{\"id\": \"evt_1\",\"type\":\"payment.captured\"}".getBytes());
    }

    @Test
    void rejectsOldAndFutureTimestampsEvenWithAValidMac() {
        long old = NOW.minus(Duration.ofMinutes(5).plusSeconds(1)).getEpochSecond();
        long future = NOW.plus(Duration.ofMinutes(5).plusSeconds(1)).getEpochSecond();
        assertInvalid(header(SECRET, old, BODY), BODY);      // a replay captured 5+ min ago
        assertInvalid(header(SECRET, future, BODY), BODY);
        long edge = NOW.minus(Duration.ofMinutes(5)).getEpochSecond();
        assertThatCode(() -> verifier.verify(header(SECRET, edge, BODY), BODY)).doesNotThrowAnyException();
    }

    @Test
    void rejectsATimestampSwappedForAFreshOne() {
        // attacker keeps an old valid MAC but writes a new t=: the MAC covers t, so it no longer matches
        long old = NOW.minus(Duration.ofHours(1)).getEpochSecond();
        assertInvalid("t=" + NOW.getEpochSecond() + ",v1=" + hmac(SECRET, old, BODY), BODY);
    }

    @Test
    void rejectsMissingOrMalformedHeaders() {
        assertInvalid(null, BODY);
        assertInvalid("", BODY);
        assertInvalid("v1=abc", BODY);
        assertInvalid("t=notanumber,v1=abc", BODY);
        assertInvalid("t=" + NOW.getEpochSecond(), BODY);
    }

    // ---- ADR 0029: secret rotation ----

    @Test
    void duringARotationTheCurrentAndThePreviousSecretAreAcceptedAndTold() {
        WebhookSignatureVerifier rotating = new WebhookSignatureVerifier("whsec_new", SECRET, Duration.ofMinutes(5),
                Clock.fixed(NOW, ZoneOffset.UTC));
        long t = NOW.getEpochSecond();

        assertThat(rotating.verify(header("whsec_new", t, BODY), BODY)).isEqualTo(WebhookSignatureVerifier.Key.CURRENT);
        assertThat(rotating.verify(header(SECRET, t, BODY), BODY)).isEqualTo(WebhookSignatureVerifier.Key.PREVIOUS);
        // a sender in the middle of its switch signs with both: one valid v1 is enough
        assertThat(rotating.verify("t=" + t + ",v1=" + hmac("whsec_new", t, BODY) + ",v1=" + hmac(SECRET, t, BODY), BODY))
                .isEqualTo(WebhookSignatureVerifier.Key.CURRENT);
        assertThat(rotating.verify("t=" + t + ",v1=" + "00".repeat(32) + ",v1=" + hmac(SECRET, t, BODY), BODY))
                .isEqualTo(WebhookSignatureVerifier.Key.PREVIOUS);
        assertThatThrownBy(() -> rotating.verify(header("whsec_older", t, BODY), BODY))
                .isInstanceOf(WebhookSignatureVerifier.InvalidSignatureException.class);
    }

    @Test
    void afterTheRotationTheOldSecretIsRefusedAndAHeaderFullOfGuessesToo() {
        long t = NOW.getEpochSecond();
        assertInvalid(header("whsec_old", t, BODY), BODY);         // no previous secret configured
        String guesses = "t=" + t + ",v1=a,v1=b,v1=c,v1=d,v1=" + hmac(SECRET, t, BODY); // > 4 signatures: refused
        assertInvalid(guesses, BODY);
        assertThat(verifier.verify(header(SECRET, t, BODY), BODY)).isEqualTo(WebhookSignatureVerifier.Key.CURRENT);
    }

    @Test
    void refusesToStartWithoutASecret() {
        assertThatThrownBy(() -> new WebhookSignatureVerifier(" ", Duration.ofMinutes(5), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
    }

    private void assertInvalid(String header, byte[] body) {
        assertThatThrownBy(() -> verifier.verify(header, body))
                .isInstanceOf(WebhookSignatureVerifier.InvalidSignatureException.class);
    }

    /** What the simulator sends (independent re-implementation of WebhookSigner). */
    static String header(String secret, long t, byte[] body) {
        return "t=" + t + ",v1=" + hmac(secret, t, body);
    }

    static String hmac(String secret, long t, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
