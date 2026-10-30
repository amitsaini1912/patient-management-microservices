package org.pm.billingservice.card;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.TestcontainersConfiguration;
import org.pm.billingservice.processor.FakePaymentProcessor;
import org.pm.billingservice.service.BillingAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Payments carry a vault token; billing keeps only its metadata, and refuses unknown/expired cards before charging. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "grpc.server.port=0")
@Import(TestcontainersConfiguration.class)
class PaymentCardIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private BillingAccountService billingAccountService;
    @Autowired private FakeCardVaultClient vault;
    @Autowired private FakePaymentProcessor processor;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();
    private UUID account;

    @BeforeEach
    void setUp() {
        account = billingAccountService.getOrCreateAccount(UUID.randomUUID(), "Card Holder", "holder@test.com").getId();
    }

    @AfterEach
    void vaultUp() {
        vault.setDown(false);
    }

    @Test
    void paymentKeepsTokenLast4BrandAndFingerprintOnly() throws Exception {
        String token = "tok_" + UUID.randomUUID().toString().replace("-", "");
        vault.register(new CardInfo(token, "4444", "MASTERCARD", "f".repeat(64), 8, 2031));

        HttpResponse<String> response = pay(newKey(), token);

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("cardLast4").asText()).isEqualTo("4444");
        assertThat(body.get("cardBrand").asText()).isEqualTo("MASTERCARD");
        assertThat(response.body()).doesNotContain("f".repeat(64)); // fingerprint stays internal
        var row = jdbc.queryForMap("SELECT card_token, card_last4, card_brand, card_fingerprint FROM payment WHERE id = ?",
                UUID.fromString(body.get("id").asText()));
        assertThat(row).containsEntry("card_token", token).containsEntry("card_last4", "4444")
                .containsEntry("card_fingerprint", "f".repeat(64));
    }

    @Test
    void twoTokensForTheSameCardShareAFingerprint() throws Exception {
        String fingerprint = UUID.randomUUID().toString().replace("-", "").repeat(2);
        vault.register(new CardInfo("tok_first_" + fingerprint.substring(0, 8), "1111", "VISA", fingerprint, 1, 2030));
        vault.register(new CardInfo("tok_second_" + fingerprint.substring(0, 8), "1111", "VISA", fingerprint, 1, 2030));

        pay(newKey(), "tok_first_" + fingerprint.substring(0, 8));
        pay(newKey(), "tok_second_" + fingerprint.substring(0, 8));

        // "has this card paid before?" - answerable without any card number
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE card_fingerprint = ?", Integer.class, fingerprint))
                .isEqualTo(2);
    }

    @Test
    void unknownOrExpiredCardIs422AndNothingIsCharged() throws Exception {
        String expired = "tok_expired_" + UUID.randomUUID();
        vault.register(new CardInfo(expired, "0005", "AMEX", "e".repeat(64), 1, 2020));
        String key1 = newKey();
        String key2 = newKey();

        HttpResponse<String> unknown = pay(key1, "tok_unknown_" + UUID.randomUUID());
        HttpResponse<String> old = pay(key2, expired);

        assertThat(unknown.statusCode()).isEqualTo(422);
        assertThat(unknown.body()).contains("unknown card token");
        assertThat(old.statusCode()).isEqualTo(422);
        assertThat(old.body()).contains("card ending 0005 has expired");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE billing_account_id = ?", Integer.class, account))
                .isZero();                                                     // no CREATED row, no authorization
    }

    @Test
    void vaultDownIs503AndTheSameKeyWorksOnceItIsBack() throws Exception {
        String key = newKey();
        vault.setDown(true);

        HttpResponse<String> down = pay(key, "pm_card_visa");

        assertThat(down.statusCode()).isEqualTo(503);
        vault.setDown(false);
        HttpResponse<String> retry = pay(key, "pm_card_visa"); // key was released, not stored as a 503
        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).isEmpty();
        assertThat(processor.executions("authorize:" + objectMapper.readTree(retry.body()).get("id").asText())).isEqualTo(1);
    }

    private HttpResponse<String> pay(String key, String cardToken) throws Exception {
        String body = """
                {"billingAccountId":"%s","amount":"100.00","currency":"INR","paymentMethodToken":"%s"}"""
                .formatted(account, cardToken);
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/payments"))
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }
}
