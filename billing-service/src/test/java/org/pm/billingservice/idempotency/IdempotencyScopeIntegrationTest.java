package org.pm.billingservice.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.TestcontainersConfiguration;
import org.pm.billingservice.service.BillingAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 12: Idempotency-Keys are per caller (X-User-Id, set by api-gateway from the JWT). Same config as
 * IdempotencyIntegrationTest, so Spring reuses that context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "grpc.server.port=0")
@Import(TestcontainersConfiguration.class)
class IdempotencyScopeIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private BillingAccountService billingAccountService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private UUID billingAccountId;

    @BeforeEach
    void setUp() {
        billingAccountId = billingAccountService
                .getOrCreateAccount(UUID.randomUUID(), "Test Patient", "patient@test.com").getId();
    }

    @Test
    void twoUsersWithTheSameKeyGetTwoPaymentsNotEachOthersResponse() throws Exception {
        String key = "order-" + UUID.randomUUID(); // a guessable-style key, now safe per user

        HttpResponse<String> alice = pay("alice@test.com", key, "100.00");
        HttpResponse<String> bob = pay("bob@test.com", key, "250.00");  // different body: before Day 12 -> 422

        assertThat(alice.statusCode()).isEqualTo(201);
        assertThat(bob.statusCode()).isEqualTo(201);
        assertThat(bob.headers().firstValue(IdempotencyFilter.REPLAYED_HEADER)).isEmpty();
        assertThat(id(bob)).isNotEqualTo(id(alice));
        assertThat(paymentsOfAccount()).isEqualTo(2);
    }

    @Test
    void sameUserSameKeyReplaysAndTheRowHoldsNoEmail() throws Exception {
        String key = "order-" + UUID.randomUUID();

        HttpResponse<String> first = pay("carol@test.com", key, "100.00");
        HttpResponse<String> again = pay("carol@test.com", key, "100.00");

        assertThat(again.headers().firstValue(IdempotencyFilter.REPLAYED_HEADER)).hasValue("true");
        assertThat(id(again)).isEqualTo(id(first));
        List<String> scopes = jdbc.queryForList("SELECT scope FROM idempotency_key WHERE idempotency_key = ?",
                String.class, key);
        assertThat(scopes).hasSize(1);
        assertThat(scopes.get(0)).matches("user:[0-9a-f]{32}").doesNotContain("carol");
    }

    @Test
    void withoutAUserTheSharedDefaultScopeAndTheOldIdsAreKept() throws Exception {
        String key = UUID.randomUUID().toString();

        HttpResponse<String> response = pay(null, key, "100.00");

        assertThat(jdbc.queryForObject("SELECT scope FROM idempotency_key WHERE idempotency_key = ?", String.class, key))
                .isEqualTo("default");
        // same id derivation as before Day 12, so payments made then are still found by their retries
        assertThat(id(response)).isEqualTo(UUID.nameUUIDFromBytes(("payment:" + key).getBytes()).toString());
    }

    private HttpResponse<String> pay(String user, String key, String amount) throws Exception {
        String body = """
                {"billingAccountId":"%s","amount":"%s","currency":"INR","paymentMethodToken":"pm_card_visa"}"""
                .formatted(billingAccountId, amount);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/payments"))
                .header("Content-Type", "application/json").header(IdempotencyFilter.HEADER, key)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (user != null) {
            request.header(IdempotencyFilter.USER_HEADER, user);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String id(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body()).get("id").asText();
    }

    private int paymentsOfAccount() {
        return jdbc.queryForObject("SELECT count(*) FROM payment WHERE billing_account_id = ?", Integer.class,
                billingAccountId);
    }
}
