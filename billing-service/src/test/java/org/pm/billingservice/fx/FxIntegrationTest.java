package org.pm.billingservice.fx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.TestcontainersConfiguration;
import org.pm.billingservice.ledger.AccountPurpose;
import org.pm.billingservice.ledger.LedgerService;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.payment.Payment;
import org.pm.billingservice.payment.PaymentService;
import org.pm.billingservice.payment.Refund;
import org.pm.billingservice.reconciliation.FakeSettlementSource;
import org.pm.billingservice.reconciliation.SettlementRow;
import org.pm.billingservice.service.BillingAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-currency ledger end to end: INR books, USD card payments (ADR 0012). The ledger and the FX rates are shared
 * with other tests, so balances are checked as deltas, and each test sets the rates it needs right before using them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "grpc.server.port=0")
@Import(TestcontainersConfiguration.class)
class FxIntegrationTest {

    private static final Currency INR = Currency.getInstance("INR");
    private static final Currency USD = Currency.getInstance("USD");

    @LocalServerPort private int port;
    @Autowired private PaymentService paymentService;
    @Autowired private BillingAccountService billingAccountService;
    @Autowired private LedgerService ledger;
    @Autowired private FxRateService rates;
    @Autowired private FxRevaluationService revaluation;
    @Autowired private FakeSettlementSource settlement;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();
    private UUID account;

    @BeforeEach
    void setUp() {
        account = billingAccountService.getOrCreateAccount(UUID.randomUUID(), "Visiting Patient", "visitor@test.com").getId();
    }

    @Test
    void usdCaptureIsBookedInInrAtTheRateLockedAtAuthorization() {
        usdRate("83.00");
        Payment payment = paymentService.authorize(newKey(), account, usd("100.00"), "pm_card_visa");
        usdRate("85.00"); // the rate moves before capture...
        Map<String, Money> before = balances();

        paymentService.capture(payment.getId(), null);

        // ...but the patient's charge stays at the locked 83.00
        assertThat(payment.getLockedRate().rate()).isEqualByComparingTo("83.00");
        assertThat(payment.getBookCurrency()).isEqualTo(INR);
        Map<String, Money> after = balances();
        assertThat(delta(before, after, "revenue")).isEqualTo(inr("8300.00"));
        assertThat(delta(before, after, "processor USD")).isEqualTo(usd("100.00"));     // the acquirer owes us USD
        assertThat(delta(before, after, "clearing USD")).isEqualTo(usd("100.00"));      // our USD position
        assertThat(delta(before, after, "clearing INR")).isEqualTo(inr("-8300.00"));    // ...bought for INR 8,300
        assertThat(ledger.balanceOf(ledger.patientReceivable(account))).isEqualTo(inr("0.00")); // charged and paid
        assertEveryCurrencyBalances();
    }

    @Test
    void refundAfterTheRateRosePostsARealizedFxLoss() {
        usdRate("83.00");
        Payment payment = captured("100.00");
        usdRate("85.00");
        Map<String, Money> before = balances();

        Refund refund = paymentService.refund(newKey(), payment.getId(), usd("50.00"), "returned");

        Map<String, Money> after = balances();
        assertThat(refund.getFxRate()).isEqualByComparingTo("85.00");
        assertThat(delta(before, after, "refunds")).isEqualTo(inr("4150.00"));      // sale reversed at locked 83
        assertThat(delta(before, after, "clearing INR")).isEqualTo(inr("4250.00")); // USD 50 left at today's 85
        assertThat(delta(before, after, "fx gain/loss")).isEqualTo(inr("-100.00")); // loss (revenue account goes down)
        assertThat(delta(before, after, "processor USD")).isEqualTo(usd("-50.00"));
        assertThat(ledger.balanceOf(ledger.patientReceivable(account))).isEqualTo(inr("0.00"));
        assertEveryCurrencyBalances();
    }

    @Test
    void refundAfterTheRateFellPostsARealizedFxGain() {
        usdRate("83.00");
        Payment payment = captured("100.00");
        usdRate("80.00");
        Map<String, Money> before = balances();

        paymentService.refund(newKey(), payment.getId(), usd("50.00"), null);

        assertThat(delta(before, balances(), "fx gain/loss")).isEqualTo(inr("150.00")); // 50 x (83 - 80)
        assertEveryCurrencyBalances();
    }

    @Test
    void inrPaymentsPostExactlyAsBeforeWithoutTouchingFx() {
        Map<String, Money> before = balances();

        Payment payment = paymentService.authorize(newKey(), account, inr("500.00"), "pm_card_visa");
        paymentService.capture(payment.getId(), null);
        paymentService.refund(newKey(), payment.getId(), inr("100.00"), null);

        Map<String, Money> after = balances();
        assertThat(payment.getLockedRate().rate()).isEqualByComparingTo("1");
        assertThat(delta(before, after, "clearing INR")).isEqualTo(inr("0.00"));
        assertThat(delta(before, after, "fx gain/loss")).isEqualTo(inr("0.00"));
        assertThat(delta(before, after, "processor INR")).isEqualTo(inr("400.00"));
    }

    @Test
    void revaluationMarksTheOpenPositionToTodaysRateAndRunningItAgainPostsNothing() throws Exception {
        usdRate("83.00");
        captured("100.00");
        revaluation.revalue(); // start from a marked position (other tests leave positions too)
        usdRate("84.00");
        Map<String, Money> before = balances();

        FxRevaluationService.Result first = revaluation.revalue();
        FxRevaluationService.Result second = revaluation.revalue();

        // carried value = what the position is worth now; the move went to FX gain/loss (unrealized)
        assertThat(first.carried().plus(first.delta())).isEqualTo(first.worthNow());
        assertThat(first.delta().isPositive()).isTrue(); // we hold USD and USD went up
        assertThat(delta(before, balances(), "fx gain/loss")).isEqualTo(first.delta());
        assertThat(second.delta().isZero()).isTrue();
        assertEveryCurrencyBalances();

        // four instances at once after the next move: still posted once (advisory lock)
        usdRate("82.00");
        Map<String, Money> beforeRace = balances();
        List<FxRevaluationService.Result> results = concurrently(4, revaluation::revalue);
        Money posted = results.stream().map(FxRevaluationService.Result::delta).reduce(Money.zero(INR), Money::plus);
        assertThat(results.stream().filter(r -> !r.delta().isZero())).hasSize(1);
        assertThat(delta(beforeRace, balances(), "fx gain/loss")).isEqualTo(posted);
        assertThat(revaluation.revalue().delta().isZero()).isTrue();
    }

    @Test
    void reconciliationComparesInTheCardsCurrency() throws Exception {
        // the acquirer reports USD; our processor receivable for this payment is in USD too -> matched
        usdRate("83.00");
        Payment payment = captured("100.00");
        settlement.add(new SettlementRow("cap_usd_" + payment.getId(), "CAPTURE", payment.getProcessorReference(),
                payment.getId().toString(), 100_00, "USD", Instant.now()));

        JsonNode run = post("/reconciliation/runs?date=" + LocalDate.now(ZoneOffset.UTC), null).body();

        List<String> references = new ArrayList<>();
        run.get("items").forEach(i -> references.add(i.get("reference").asText()));
        assertThat(references).doesNotContain(payment.getId().toString());
    }

    @Test
    void ratesApi() throws Exception {
        assertThat(post("/fx/rates", "{\"base\":\"EUR\",\"quote\":\"INR\",\"rate\":\"91.25\",\"source\":\"manual\"}").status())
                .isEqualTo(201);
        JsonNode current = get("/fx/rates/EUR/INR").body();
        assertThat(current.get("rate").asText()).isEqualTo("91.25");
        assertThat(current.get("source").asText()).isEqualTo("manual");

        assertThat(post("/fx/rates", "{\"base\":\"EUR\",\"quote\":\"INR\",\"rate\":\"0\",\"source\":\"x\"}").status()).isEqualTo(400);
        assertThat(post("/fx/rates", "{\"base\":\"INR\",\"quote\":\"INR\",\"rate\":\"1\",\"source\":\"x\"}").status()).isEqualTo(400);
        assertThat(post("/fx/rates", "{\"base\":\"GBP\",\"quote\":\"INR\",\"rate\":\"105\",\"source\":\"x\"}").status()).isEqualTo(400);
        assertThat(post("/fx/rates", "{\"base\":\"USD\",\"quote\":\"INR\",\"rate\":\"8.3e1\",\"source\":\"x\"}").status()).isEqualTo(400);
        assertThat(get("/fx/rates/USD/EUR").status()).isEqualTo(422); // no cross rate on file
    }

    // ---- helpers ----

    private Payment captured(String usdAmount) {
        Payment payment = paymentService.authorize(newKey(), account, usd(usdAmount), "pm_card_visa");
        return paymentService.capture(payment.getId(), null);
    }

    private void usdRate(String rate) {
        rates.record(USD, INR, new BigDecimal(rate), "test", null);
    }

    /** The accounts these tests look at, by short name. */
    private Map<String, Money> balances() {
        return Map.of(
                "revenue", ledger.balanceOf(ledger.systemAccount(AccountPurpose.HOSPITAL_REVENUE, INR)),
                "refunds", ledger.balanceOf(ledger.systemAccount(AccountPurpose.REFUNDS, INR)),
                "fx gain/loss", ledger.balanceOf(ledger.systemAccount(AccountPurpose.FX_GAIN_LOSS, INR)),
                "clearing INR", ledger.balanceOf(ledger.systemAccount(AccountPurpose.FX_CLEARING, INR)),
                "clearing USD", ledger.balanceOf(ledger.systemAccount(AccountPurpose.FX_CLEARING, USD)),
                "processor INR", ledger.balanceOf(ledger.systemAccount(AccountPurpose.PROCESSOR_RECEIVABLE, INR)),
                "processor USD", ledger.balanceOf(ledger.systemAccount(AccountPurpose.PROCESSOR_RECEIVABLE, USD)));
    }

    private static Money delta(Map<String, Money> before, Map<String, Money> after, String account) {
        return after.get(account).minus(before.get(account));
    }

    /** Trial balance: in EVERY currency, all debits ever posted = all credits. */
    private void assertEveryCurrencyBalances() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT currency, SUM(CASE WHEN direction = 'DEBIT' THEN amount_minor ELSE -amount_minor END) AS net
                FROM entry_line GROUP BY currency""");
        assertThat(rows).isNotEmpty().allSatisfy(r -> assertThat(((Number) r.get("net")).longValue())
                .as("net of all %s lines", r.get("currency")).isZero());
    }

    private interface Call<T> {
        T call() throws Exception;
    }

    private static <T> List<T> concurrently(int threads, Call<T> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private record Response(int status, JsonNode body) {}

    private Response post(String path, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body().isEmpty() ? null : objectMapper.readTree(response.body()));
    }

    private Response get(String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), objectMapper.readTree(response.body()));
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static Money usd(String amount) {
        return Money.parse(amount, "USD");
    }

    private static Money inr(String amount) {
        return Money.parse(amount, "INR");
    }
}
