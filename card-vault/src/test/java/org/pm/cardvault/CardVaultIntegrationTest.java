package org.pm.cardvault;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.pm.cardvault.crypto.CardCipher;
import org.pm.cardvault.crypto.KeyRing;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The vault's API against real Postgres. Keys and callers come from src/test/resources/config/application.properties. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CardVaultIntegrationTest {

    static final String GATEWAY = "test-gateway-key-0123456789";
    static final String BILLING = "test-billing-key-0123456789";
    static final String SIMULATOR = "test-simulator-key-0123456789";
    static final String VISA = "4111111111111111";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Value("${vault.encryption-keys}") private String keys;

    @Test
    void tokenizeReturnsTokenAndMetadataButNeverThePan() throws Exception {
        String body = tokenize(GATEWAY, "4111 1111 1111 1111", 12, 2030)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value(org.hamcrest.Matchers.matchesPattern("tok_[a-z2-7]{26}")))
                .andExpect(jsonPath("$.last4").value("1111"))
                .andExpect(jsonPath("$.brand").value("VISA"))
                .andExpect(jsonPath("$.fingerprint").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")))
                .andExpect(jsonPath("$.expiry_month").value(12))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(VISA);
    }

    @Test
    void sameCardTwiceGivesTwoTokensWithOneFingerprint() throws Exception {
        JsonNode a = json(tokenize(GATEWAY, VISA, 1, 2031));
        JsonNode b = json(tokenize(GATEWAY, VISA, 1, 2031));
        JsonNode other = json(tokenize(GATEWAY, "5555555555554444", 1, 2031));

        assertThat(a.get("token")).isNotEqualTo(b.get("token"));
        assertThat(a.get("fingerprint")).isEqualTo(b.get("fingerprint"));        // duplicate detection works
        assertThat(other.get("fingerprint")).isNotEqualTo(a.get("fingerprint"));
        assertThat(other.get("brand").asText()).isEqualTo("MASTERCARD");
    }

    @Test
    void tokensNeverLookLikeACardNumber() throws Exception {
        // hex tokens failed this ~0.6% of the time (a Luhn-valid 13+ digit run inside the token)
        for (int i = 0; i < 300; i++) {
            String token = json(tokenize(GATEWAY, VISA, 12, 2030)).get("token").asText();
            assertThat(token).matches("tok_[a-z2-7]{26}").doesNotContainPattern("\\d{13}");
        }
    }

    @Test
    void invalidCardsAre422WithMessagesThatDontRepeatTheNumber() throws Exception {
        String typo = tokenize(GATEWAY, "4111111111111112", 12, 2030)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("card number fails the check digit"))
                .andReturn().getResponse().getContentAsString();
        assertThat(typo).doesNotContain("4111");
        tokenize(GATEWAY, VISA, 1, 2020).andExpect(jsonPath("$.detail").value("card has expired"));
        tokenize(GATEWAY, VISA, 13, 2030).andExpect(status().isUnprocessableEntity());
        // a PAN in the wrong field: Jackson's error would quote it; ours doesn't
        String misplaced = mvc.perform(post("/cards").header("X-Api-Key", GATEWAY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pan\":\"x\",\"expiry_month\":\"" + VISA + "\",\"expiry_year\":2030}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(misplaced).doesNotContain(VISA);
    }

    @Test
    void eachCallerCanOnlyDoItsOwnJob() throws Exception {
        String token = json(tokenize(GATEWAY, VISA, 12, 2030)).get("token").asText();

        // no key / unknown key: 401
        mvc.perform(get("/cards/" + token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/cards/" + token).header("X-Api-Key", "not-a-real-key-at-all")).andExpect(status().isUnauthorized());
        // billing reads metadata, nothing else
        mvc.perform(get("/cards/" + token).header("X-Api-Key", BILLING))
                .andExpect(status().isOk()).andExpect(jsonPath("$.last4").value("1111"));
        tokenize(BILLING, VISA, 12, 2030).andExpect(status().isForbidden());
        detokenize(BILLING, token).andExpect(status().isForbidden());
        // the gateway tokenizes, nothing else
        mvc.perform(get("/cards/" + token).header("X-Api-Key", GATEWAY)).andExpect(status().isForbidden());
        detokenize(GATEWAY, token).andExpect(status().isForbidden());
        // only the simulator (the card network) gets the number back
        detokenize(SIMULATOR, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pan").value(VISA))
                .andExpect(jsonPath("$.expiry_year").value(2030));
        mvc.perform(get("/cards/tok_nope").header("X-Api-Key", BILLING)).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()); // health needs no key
    }

    @Test
    void everyDetokenizationIsAuditedAndRefusalsAreNot() throws Exception {
        String token = json(tokenize(GATEWAY, VISA, 12, 2030)).get("token").asText();

        detokenize(BILLING, token).andExpect(status().isForbidden());
        detokenize(SIMULATOR, token).andExpect(status().isOk());
        detokenize(SIMULATOR, token).andExpect(status().isOk());

        assertThat(jdbc.queryForList("SELECT client_name FROM detokenize_audit WHERE token = ?", String.class, token))
                .containsExactly("simulator", "simulator");
    }

    @Test
    void theDatabaseHoldsNoPlaintextPan() throws Exception {
        String token = json(tokenize(GATEWAY, VISA, 12, 2030)).get("token").asText();

        // everything in the row, as text, contains no PAN (only last4, which is allowed)
        String row = jdbc.queryForMap("SELECT * FROM card WHERE token = ?", token).values().stream()
                .map(v -> v instanceof byte[] bytes ? new String(bytes, StandardCharsets.ISO_8859_1) : String.valueOf(v))
                .reduce("", String::concat);
        assertThat(row).doesNotContain(VISA).doesNotContain("41111111");
        assertThat(jdbc.queryForObject("SELECT key_version FROM card WHERE token = ?", Integer.class, token)).isEqualTo(2);
    }

    @Test
    void cardsEncryptedBeforeTheKeyRotationAreStillReadable() throws Exception {
        // a card stored back when version 1 was the active key
        String v1Only = keys.split(",")[0];
        CardCipher oldCipher = new CardCipher(KeyRing.parse(v1Only, 1));
        String token = "tok_rotationtestcardkeyversion1";
        CardCipher.Encrypted e = oldCipher.encrypt("5555555555554444", token);
        jdbc.update("""
                INSERT INTO card (token, pan_ciphertext, iv, key_version, fingerprint, last4, brand, expiry_month, expiry_year)
                VALUES (?, ?, ?, 1, ?, '4444', 'MASTERCARD', 6, 2032)""", token, e.ciphertext(), e.iv(), "f".repeat(64));

        detokenize(SIMULATOR, token).andExpect(status().isOk()).andExpect(jsonPath("$.pan").value("5555555555554444"));
        // and new cards use version 2
        String fresh = json(tokenize(GATEWAY, VISA, 12, 2030)).get("token").asText();
        assertThat(jdbc.queryForObject("SELECT key_version FROM card WHERE token = ?", Integer.class, fresh)).isEqualTo(2);
    }

    @Test
    void ciphertextTamperedInTheDatabaseIsDetectedNotDecryptedToGarbage() throws Exception {
        String a = json(tokenize(GATEWAY, VISA, 12, 2030)).get("token").asText();
        String b = json(tokenize(GATEWAY, "5555555555554444", 12, 2030)).get("token").asText();

        // someone with DB access copies card B's ciphertext onto card A's row
        jdbc.update("UPDATE card SET pan_ciphertext = (SELECT pan_ciphertext FROM card WHERE token = ?), "
                + "iv = (SELECT iv FROM card WHERE token = ?) WHERE token = ?", b, b, a);

        String body = detokenize(SIMULATOR, a).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Card data unreadable"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("5555555555554444");
    }

    ResultActions tokenize(String apiKey, String pan, int month, int year) throws Exception {
        return mvc.perform(post("/cards").header("X-Api-Key", apiKey).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pan\":\"%s\",\"expiry_month\":%d,\"expiry_year\":%d}".formatted(pan, month, year)));
    }

    ResultActions detokenize(String apiKey, String token) throws Exception {
        return mvc.perform(post("/cards/" + token + "/detokenize").header("X-Api-Key", apiKey));
    }

    JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
