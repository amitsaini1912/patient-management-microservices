package org.pm.cardvault.card;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.pm.cardvault.crypto.CardCipher;
import org.pm.cardvault.crypto.Fingerprinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tokenize / read metadata / detokenize. Logs mention tokens, brands and last4 - never the PAN
 * (and the log layout masks anything that looks like one, as a second line of defence).
 */
@Service
public class CardService {

    private static final Logger log = LoggerFactory.getLogger(CardService.class);

    private final CardRepository repository;
    private final CardCipher cipher;
    private final Fingerprinter fingerprinter;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public CardService(CardRepository repository, CardCipher cipher, Fingerprinter fingerprinter, Clock clock) {
        this.repository = repository;
        this.cipher = cipher;
        this.fingerprinter = fingerprinter;
        this.clock = clock;
    }

    /** A new token every time, even for a card seen before (decision: same fingerprint, different token). */
    public CardMetadata tokenize(String rawPan, int expiryMonth, int expiryYear) {
        String pan = normalize(rawPan);
        validateExpiry(expiryMonth, expiryYear);
        String token = newToken();
        CardMetadata card = new CardMetadata(token, pan.substring(pan.length() - 4), CardBrand.of(pan),
                fingerprinter.fingerprint(pan), expiryMonth, expiryYear, clock.instant());
        repository.insert(card, cipher.encrypt(pan, token));
        log.info("tokenized {} card ending {} as {}", card.brand(), card.last4(), token);
        return card;
    }

    public CardMetadata metadata(String token) {
        return repository.metadata(token).orElseThrow(() -> new CardNotFoundException(token));
    }

    public record CardDetails(String pan, int expiryMonth, int expiryYear) {
        @Override
        public String toString() {
            return "CardDetails{pan=****" + pan.substring(pan.length() - 4) + ", expiry=" + expiryMonth + "/" + expiryYear + "}";
        }
    }

    /** The PAN leaves the vault: audited in the same transaction, so there's no detokenization without a record. */
    @Transactional
    public CardDetails detokenize(String token, String clientName) {
        CardMetadata card = metadata(token);
        String pan = cipher.decrypt(repository.encryptedPan(token).orElseThrow(), token);
        repository.audit(token, clientName);
        log.info("detokenized {} for {}", token, clientName);
        return new CardDetails(pan, card.expiryMonth(), card.expiryYear());
    }

    /** Digits only (spaces and dashes allowed in input), 13-19 long, Luhn-valid. Messages never contain the number. */
    static String normalize(String raw) {
        if (raw == null) {
            throw new InvalidCardException("card number is required");
        }
        StringBuilder digits = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else if (c != ' ' && c != '-') {
                throw new InvalidCardException("card number may only contain digits, spaces and dashes");
            }
        }
        if (digits.length() < 13 || digits.length() > 19) {
            throw new InvalidCardException("card number must have 13 to 19 digits");
        }
        if (!Luhn.isValid(digits)) {
            throw new InvalidCardException("card number fails the check digit");
        }
        return digits.toString();
    }

    private void validateExpiry(int month, int year) {
        if (month < 1 || month > 12 || year < 2000 || year > 2100) {
            throw new InvalidCardException("expiry must be a month 1-12 and a four-digit year");
        }
        // a card is valid through the last day of its expiry month
        if (YearMonth.of(year, month).isBefore(YearMonth.now(clock.withZone(ZoneOffset.UTC)))) {
            throw new InvalidCardException("card has expired");
        }
    }

    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();
    private static final java.util.regex.Pattern PAN_LIKE_RUN = java.util.regex.Pattern.compile("\\d{13}");

    /**
     * "tok_" + 128 random bits in lowercase base32 (26 chars). Not hex on purpose: 0.6% of hex tokens contain a
     * 13+ digit run that passes Luhn, which the log masker then hides as if it were a PAN (measured on Day 10).
     * Base32 has only 6 digits in its alphabet; the loop makes "never 13 digits in a row" a guarantee, not odds.
     */
    private String newToken() {
        while (true) {
            byte[] bytes = randomBytes(16);
            StringBuilder token = new StringBuilder("tok_");
            int buffer = 0;
            int bits = 0;
            for (byte b : bytes) {
                buffer = (buffer << 8) | (b & 0xff);
                bits += 8;
                while (bits >= 5) {
                    token.append(BASE32[(buffer >> (bits - 5)) & 31]);
                    bits -= 5;
                }
            }
            if (bits > 0) {
                token.append(BASE32[(buffer << (5 - bits)) & 31]);
            }
            if (!PAN_LIKE_RUN.matcher(token).find()) {
                return token.toString();
            }
        }
    }

    private byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        random.nextBytes(bytes);
        return bytes;
    }
}
