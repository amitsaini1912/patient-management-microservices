package org.pm.simulator.network;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.pm.simulator.card.FakeCardVaultClient;
import org.pm.simulator.issuer.Issuer;
import org.pm.simulator.webhook.WebhookDispatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Acquirer API -> card network -> issuer, in one JVM (fake vault). Short timeouts: the issuer must answer within
 * 300 ms, the slow test card answers after 800 ms. Webhooks go nowhere (one attempt); we read the dispatcher's log.
 * Tests that look at balances use a fresh random card, so they don't see each other's holds.
 */
@SpringBootTest(properties = {
        "simulator.api-key=test-api-key",
        "simulator.webhooks.secret=test-webhook-secret",
        "simulator.webhooks.target-url=http://localhost:1/nobody",
        "simulator.webhooks.delay=10ms",
        "simulator.webhooks.max-attempts=1",
        "simulator.card-vault.mode=fake",
        "simulator.network.issuer-timeout=300ms",
        "simulator.issuer.slow-response=800ms"})
@AutoConfigureMockMvc
class NetworkIntegrationTest {

    private static final String API_KEY = "Bearer test-api-key";
    private static final long DEFAULT_BALANCE = 100_000_000L; // 10,00,000.00 INR

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private Issuer issuer;
    @Autowired private NetworkLog messages;
    @Autowired private FakeCardVaultClient vault;
    @Autowired private WebhookDispatcher dispatcher;

    @AfterEach
    void vaultUp() {
        vault.setDown(false);
    }

    // ---- field 39 -> what the merchant sees ----

    @ParameterizedTest(name = "{0} -> {1} {2}")
    @CsvSource(nullValues = "null", value = {
            "pm_card_visa,               AUTHORIZED, 00, null,               false",
            "pm_card_declined,           DECLINED,   05, card_declined,      false",
            "pm_card_insufficient_funds, DECLINED,   51, insufficient_funds, false",
            "pm_card_expired,            DECLINED,   54, expired_card,       false",
            "pm_card_issuer_unavailable, DECLINED,   91, issuer_unavailable, true"})
    void eachResponseCodeBecomesAStatusAReasonAndARetryableFlag(String token, String status, String code,
                                                                 String reason, boolean retryable) throws Exception {
        String reference = UUID.randomUUID().toString();

        JsonNode auth = body(authorize(reference, 500_00, "INR", token).andExpect(status().isOk()));

        assertThat(auth.get("status").asText()).isEqualTo(status);
        assertThat(auth.get("network_response_code").asText()).isEqualTo(code);
        assertThat(auth.path("decline_reason").isMissingNode() ? null : auth.get("decline_reason").asText()).isEqualTo(reason);
        assertThat(auth.get("retryable").asBoolean()).isEqualTo(retryable);
        assertThat(auth.has("approval_code")).isEqualTo(code.equals("00"));
        assertThat(auth.get("stan").asText()).matches("\\d{6}");
        assertThat(auth.get("rrn").asText()).matches("\\d{12}").endsWith(auth.get("stan").asText());

        // the webhook says the same, so billing learns it even if this response is lost
        Map<String, Object> data = awaitWebhookData(reference);
        assertThat(data).containsEntry("network_response_code", code).containsEntry("retryable", retryable);
    }

    @Test
    void the0100And0110ShareStanAndRrnAndTheLogNeverHasThePan() throws Exception {
        String rrn = body(authorize(UUID.randomUUID().toString(), 100_00, "INR", "pm_card_visa")).get("rrn").asText();

        List<NetworkLog.Entry> exchange = messagesFor(rrn);
        assertThat(exchange).extracting(NetworkLog.Entry::mti).containsExactly("0100", "0110");
        assertThat(exchange.get(0).card()).isEqualTo("****1111");
        assertThat(exchange.get(1).stan()).isEqualTo(exchange.get(0).stan());
        assertThat(exchange.get(1).responseCode()).isEqualTo("00");
        String admin = mvc.perform(get("/admin/network/messages").header("Authorization", API_KEY))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(admin).contains(rrn).doesNotContain(Issuer.VISA);
    }

    // ---- holds at the issuer ----

    @Test
    void approvalHoldsTheMoneyAndAVoidReversalReleasesIt() throws Exception {
        String pan = newCard("tok_void");

        String auth = body(authorize(UUID.randomUUID().toString(), 1_000_00, "INR", "tok_void")).get("id").asText();
        assertThat(account(pan).heldMinor()).isEqualTo(1_000_00);
        assertThat(account(pan).availableMinor()).isEqualTo(DEFAULT_BALANCE - 1_000_00);

        call("/v1/authorizations/" + auth + "/void", "").andExpect(status().isOk());

        assertThat(account(pan).heldMinor()).isZero();
        assertThat(account(pan).availableMinor()).isEqualTo(DEFAULT_BALANCE);
        assertThat(account(pan).balanceMinor()).isEqualTo(DEFAULT_BALANCE); // nothing was ever taken
        String rrn = body(mvc.perform(get("/v1/authorizations/" + auth).header("Authorization", API_KEY))).get("rrn").asText();
        assertThat(messagesFor(rrn)).extracting(NetworkLog.Entry::mti).containsExactly("0100", "0110", "0400", "0410");
        assertThat(messagesFor(rrn).get(2).note()).isEqualTo("VOID");
    }

    @Test
    void partialCaptureTakesPartAndReleasesTheRestRefundCreditsTheCard() throws Exception {
        String pan = newCard("tok_capture");
        String auth = body(authorize(UUID.randomUUID().toString(), 1_000_00, "INR", "tok_capture")).get("id").asText();

        call("/v1/authorizations/" + auth + "/capture", "{\"amount_minor\":60000,\"currency\":\"INR\"}").andExpect(status().isOk());

        assertThat(account(pan).balanceMinor()).isEqualTo(DEFAULT_BALANCE - 600_00);
        assertThat(account(pan).heldMinor()).isZero(); // the other 400.00 of the hold is released

        call("/v1/authorizations/" + auth + "/refunds", "{\"amount_minor\":20000,\"currency\":\"INR\"}").andExpect(status().isOk());

        assertThat(account(pan).balanceMinor()).isEqualTo(DEFAULT_BALANCE - 400_00);
    }

    @Test
    void moreThanIsAvailableIs51EvenOnAGoodCard() throws Exception {
        newCard("tok_big");
        authorize(UUID.randomUUID().toString(), DEFAULT_BALANCE - 100, "INR", "tok_big")
                .andExpect(jsonPath("$.network_response_code").value("00"));

        authorize(UUID.randomUUID().toString(), 101, "INR", "tok_big")       // 1 paisa more than is left
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.network_response_code").value("51"));
    }

    // ---- timeout: 91 + reversal ----

    @Test
    void slowIssuerTimesOutAs91AndTheTimeoutReversalReleasesItsHold() throws Exception {
        long availableBefore = issuer.account(Issuer.SLOW).orElseThrow().availableMinor();
        Instant start = Instant.now();

        JsonNode auth = body(authorize(UUID.randomUUID().toString(), 2_000_00, "INR", "pm_card_slow"));

        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofMillis(800)); // didn't wait for the issuer
        assertThat(auth.get("status").asText()).isEqualTo("DECLINED");
        assertThat(auth.get("network_response_code").asText()).isEqualTo("91");
        assertThat(auth.get("retryable").asBoolean()).isTrue();
        String rrn = auth.get("rrn").asText();
        assertThat(messagesFor(rrn)).extracting(NetworkLog.Entry::mti).containsExactly("0100", "0400", "0410", "0110");
        assertThat(messagesFor(rrn).get(1).note()).isEqualTo("TIMEOUT");
        assertThat(messagesFor(rrn).get(3).note()).startsWith("stand-in");
        // the issuer DID approve and hold; the reversal released it
        assertThat(issuer.account(Issuer.SLOW).orElseThrow().availableMinor()).isEqualTo(availableBefore);

        // the issuer's own (late) 0110 arrives afterwards and changes nothing
        await(() -> messagesFor(rrn).size() == 5);
        assertThat(messagesFor(rrn).get(4).note()).startsWith("late");
        assertThat(messagesFor(rrn).get(4).responseCode()).isEqualTo("00");
        assertThat(issuer.account(Issuer.SLOW).orElseThrow().availableMinor()).isEqualTo(availableBefore);
    }

    @Test
    void a91IsStoredUnderItsKeyRetryingNeedsANewKey() throws Exception {
        String key = UUID.randomUUID().toString();
        String reference = UUID.randomUUID().toString();
        String first = authorize(key, reference, 100_00, "INR", "pm_card_issuer_unavailable")
                .andReturn().getResponse().getContentAsString();

        String again = authorize(key, reference, 100_00, "INR", "pm_card_issuer_unavailable")
                .andReturn().getResponse().getContentAsString();

        assertThat(again).isEqualTo(first); // same key = same answer, even for a retryable decline
    }

    // ---- field 6: cardholder billing amount ----

    @Test
    void foreignCurrencyIsHeldInTheCardsCurrencyAtTheSchemeRate() throws Exception {
        String pan = newCard("tok_usd");

        String auth = body(authorize(UUID.randomUUID().toString(), 100_00, "USD", "tok_usd")).get("id").asText();

        assertThat(account(pan).heldMinor()).isEqualTo(8_350_00);  // USD 100.00 x 83.50 = INR 8,350.00
        call("/v1/authorizations/" + auth + "/capture", "{\"amount_minor\":4000,\"currency\":\"USD\"}");
        assertThat(account(pan).balanceMinor()).isEqualTo(DEFAULT_BALANCE - 3_340_00); // USD 40.00 = INR 3,340.00

        authorize(UUID.randomUUID().toString(), 100_00, "GBP", "tok_usd")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("currency_not_supported"));
    }

    // ---- card-vault ----

    @Test
    void vaultDownIs503AndTheSameKeyWorksOnceItIsBack() throws Exception {
        String key = UUID.randomUUID().toString();
        String reference = UUID.randomUUID().toString();
        vault.setDown(true);

        authorize(key, reference, 100_00, "INR", "pm_card_visa")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("card_vault_unavailable"));

        vault.setDown(false);
        authorize(key, reference, 100_00, "INR", "pm_card_visa")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
        authorize(UUID.randomUUID().toString(), 100_00, "INR", "tok_unknown_1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("card_not_found"));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void noFullCardNumberIsEverLogged(CapturedOutput output) throws Exception {
        for (String token : List.of("pm_card_visa", "pm_card_declined", "pm_card_expired", "pm_card_issuer_unavailable",
                "pm_card_slow")) {
            String auth = body(authorize(UUID.randomUUID().toString(), 100_00, "INR", token)).get("id").asText();
            if (token.equals("pm_card_visa")) {
                call("/v1/authorizations/" + auth + "/void", "");
            }
        }
        Thread.sleep(900); // the slow card's late 0110 is logged too

        assertThat(output.getAll()).contains("****1111", "****0911")
                .doesNotContain(Issuer.VISA, Issuer.DO_NOT_HONOR, Issuer.EXPIRED, Issuer.UNAVAILABLE, Issuer.SLOW);
    }

    // ---- helpers ----

    /** A Luhn-valid Visa nobody else uses, behind {@code token}. */
    private String newCard(String token) {
        StringBuilder digits = new StringBuilder("4");
        for (int i = 0; i < 14; i++) {
            digits.append(ThreadLocalRandom.current().nextInt(10));
        }
        for (int check = 0; check <= 9; check++) {
            if (luhnValid(digits.toString() + check)) {
                String pan = digits.toString() + check;
                vault.register(token, pan, YearMonth.of(2099, 12));
                return pan;
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private static boolean luhnValid(String pan) {
        int sum = 0;
        for (int i = 0; i < pan.length(); i++) {
            int d = pan.charAt(pan.length() - 1 - i) - '0';
            if (i % 2 == 1) {
                d = d * 2 > 9 ? d * 2 - 9 : d * 2;
            }
            sum += d;
        }
        return sum % 10 == 0;
    }

    private Issuer.AccountView account(String pan) {
        return issuer.account(pan).orElseThrow();
    }

    private List<NetworkLog.Entry> messagesFor(String rrn) {
        return messages.entries().stream().filter(e -> rrn.equals(e.rrn())).toList();
    }

    private Map<String, Object> awaitWebhookData(String reference) throws InterruptedException {
        BooleanSupplier found = () -> dispatcher.deliveries().stream()
                .anyMatch(d -> reference.equals(d.event().data().get("merchant_reference")));
        await(found);
        return dispatcher.deliveries().stream().map(d -> d.event().data())
                .filter(data -> reference.equals(data.get("merchant_reference"))).findFirst().orElseThrow();
    }

    private ResultActions authorize(String reference, long amountMinor, String currency, String token) throws Exception {
        return authorize(UUID.randomUUID().toString(), reference, amountMinor, currency, token);
    }

    private ResultActions authorize(String key, String reference, long amountMinor, String currency, String token)
            throws Exception {
        return mvc.perform(post("/v1/authorizations").header("Authorization", API_KEY).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"merchant_reference":"%s","amount_minor":%d,"currency":"%s","payment_method_token":"%s"}"""
                        .formatted(reference, amountMinor, currency, token)));
    }

    private ResultActions call(String path, String body) throws Exception {
        return mvc.perform(post(path).header("Authorization", API_KEY).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(5);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met within 5s");
            }
            Thread.sleep(20);
        }
    }
}
