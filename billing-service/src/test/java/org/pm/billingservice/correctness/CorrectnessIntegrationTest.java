package org.pm.billingservice.correctness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.TestcontainersConfiguration;
import org.pm.billingservice.audit.AuditVerifier;
import org.pm.billingservice.idempotency.IdempotencyFilter;
import org.pm.billingservice.ledger.AccountPurpose;
import org.pm.billingservice.ledger.LedgerService;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.processor.FakePaymentProcessor;
import org.pm.billingservice.service.BillingAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 21 (ADR 0021): the money is exact under load. Real Tomcat threads, real Postgres, the fake acquirer with a little
 * latency so requests overlap. Clients behave like real ones: each logical request is sent by several "retrying"
 * clients at once with the same Idempotency-Key, and each of them re-sends while the answer is "in progress" (409 +
 * Retry-After) or a 5xx. Then everything that must agree is checked: our rows, the acquirer, the ledger, the outbox,
 * the audit chain.
 * Same context as IdempotencyIntegrationTest (RANDOM_PORT).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "grpc.server.port=0")
@Import(TestcontainersConfiguration.class)
class CorrectnessIntegrationTest {

    private static final Currency INR = Currency.getInstance("INR");
    private static final int THREADS = 48;

    @LocalServerPort private int port;
    @Autowired private BillingAccountService billingAccountService;
    @Autowired private FakePaymentProcessor processor;
    @Autowired private LedgerService ledger;
    @Autowired private AuditVerifier auditVerifier;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<Integer, Integer> statuses = new ConcurrentHashMap<>();   // every FINAL status seen

    @AfterEach
    void resetLatency() {
        processor.setLatency(Duration.ZERO);
    }

    record Answer(int status, JsonNode body) {}

    // ---- 1. many payments, captures and refunds on ONE account, each sent by retrying duplicates ----

    @Test
    void aStormOfPaymentsCapturesAndRefundsOnOneAccountAddsUpToThePaisa() throws Exception {
        UUID account = billingAccountService.getOrCreateAccount(UUID.randomUUID(), "Storm Patient", "storm@test.com").getId();
        Map<String, Money> before = systemBalances();
        processor.setLatency(Duration.ofMillis(15));
        Random random = new Random(21);
        int payments = 30, duplicates = 4;

        // authorize: 30 payments (every 6th card is declined) x 4 duplicate senders
        Map<String, String> bodies = new LinkedHashMap<>();
        for (int i = 0; i < payments; i++) {
            String amount = (1_000 + random.nextInt(8_000)) + "." + String.format("%02d", random.nextInt(100));
            String card = i % 6 == 5 ? FakePaymentProcessor.DECLINED_TOKEN : "pm_card_visa";
            bodies.put("storm-auth-" + UUID.randomUUID(), """
                    {"billingAccountId":"%s","amount":"%s","currency":"INR","paymentMethodToken":"%s"}""".formatted(account, amount, card));
        }
        List<Callable<Map.Entry<String, Answer>>> calls = new ArrayList<>();
        bodies.forEach((key, body) -> {
            for (int d = 0; d < duplicates; d++) {
                calls.add(() -> Map.entry(key, client("/payments", key, body)));
            }
        });
        Map<String, Set<String>> idsPerKey = new ConcurrentHashMap<>();
        for (Map.Entry<String, Answer> r : concurrently(calls)) {
            assertThat(r.getValue().status()).as("authorize").isEqualTo(201);
            idsPerKey.computeIfAbsent(r.getKey(), k -> ConcurrentHashMap.newKeySet()).add(r.getValue().body().get("id").asText());
        }
        assertThat(idsPerKey.values()).allSatisfy(ids -> assertThat(ids).hasSize(1));      // every duplicate: same payment
        List<String> ids = idsPerKey.values().stream().map(s -> s.iterator().next()).toList();
        assertThat(paymentsOf(account)).isEqualTo(payments);

        // capture every authorized payment: 3 duplicate senders each
        List<String> authorized = ids.stream().filter(id -> "AUTHORIZED".equals(status(id))).toList();
        calls.clear();
        for (String id : authorized) {
            String key = "storm-cap-" + UUID.randomUUID();
            for (int d = 0; d < 3; d++) {
                calls.add(() -> Map.entry(id, client("/payments/" + id + "/capture", key, "")));
            }
        }
        concurrently(calls).forEach(r -> assertThat(r.getValue().status()).as("capture").isEqualTo(200));

        // refunds, all at once: 30% and 25% (3 duplicate senders each) + a competing 60% (one sender) per payment.
        // Which ones win depends on timing; what must hold doesn't.
        calls.clear();
        for (String id : authorized) {
            long captured = minor(id, "captured_amount_minor");
            for (int[] spec : new int[][] {{30, 3}, {25, 3}, {60, 1}}) {
                String key = "storm-ref-" + UUID.randomUUID();
                String body = "{\"amount\":\"%s\",\"currency\":\"INR\"}".formatted(Money.ofMinor(captured * spec[0] / 100, "INR").toDecimal());
                for (int d = 0; d < spec[1]; d++) {
                    calls.add(() -> Map.entry(id, client("/payments/" + id + "/refunds", key, body)));
                }
            }
        }
        for (Map.Entry<String, Answer> r : concurrently(calls)) {
            assertThat(r.getValue().status()).as("refund %s", r.getValue().body()).isIn(201, 400, 409);
        }

        assertThat(statuses.keySet()).as("final statuses seen").allSatisfy(s -> assertThat(s).isLessThan(500));
        assertMoneyAgrees(account, ids, before);
    }

    // ---- 2. 120 concurrent refunds on ONE payment: exactly the captured amount goes back ----

    @Test
    void oneHundredTwentyConcurrentRefundsOnOnePaymentRefundExactlyWhatWasCaptured() throws Exception {
        UUID account = billingAccountService.getOrCreateAccount(UUID.randomUUID(), "Refund Patient", "refunds@test.com").getId();
        Map<String, Money> before = systemBalances();
        String id = client("/payments", "one-" + UUID.randomUUID(), """
                {"billingAccountId":"%s","amount":"10000.00","currency":"INR","paymentMethodToken":"pm_card_visa"}""".formatted(account))
                .body().get("id").asText();
        client("/payments/" + id + "/capture", "one-cap-" + UUID.randomUUID(), "");
        processor.setLatency(Duration.ofMillis(10));

        List<Callable<Map.Entry<String, Answer>>> calls = new ArrayList<>();
        for (int i = 0; i < 120; i++) {   // 120 x INR 100 against INR 10,000: 100 can succeed
            String key = "one-ref-" + UUID.randomUUID();
            calls.add(() -> Map.entry(id, client("/payments/" + id + "/refunds", key, "{\"amount\":\"100.00\",\"currency\":\"INR\"}")));
        }
        Map<Integer, Long> byStatus = concurrently(calls).stream()
                .collect(Collectors.groupingBy(r -> r.getValue().status(), Collectors.counting()));

        assertThat(byStatus.get(201)).isEqualTo(100L);
        assertThat(byStatus.keySet()).as("losers: 400 (checked before the acquirer) or 409 (refused by it)")
                .isSubsetOf(201, 400, 409);
        assertThat(status(id)).isEqualTo("REFUNDED");
        assertMoneyAgrees(account, List.of(id), before);
    }

    // ---- what must agree ----

    private void assertMoneyAgrees(UUID account, List<String> ids, Map<String, Money> before) {
        long capturedTotal = 0, refundedTotal = 0;
        for (String id : ids) {
            UUID paymentId = UUID.fromString(id);
            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT status, captured_amount_minor, refunded_amount_minor, processor_reference FROM payment WHERE id = ?""", paymentId);
            long captured = ((Number) row.get("captured_amount_minor")).longValue();
            long refunded = ((Number) row.get("refunded_amount_minor")).longValue();
            String reference = (String) row.get("processor_reference");
            capturedTotal += captured;
            refundedTotal += refunded;

            // zero double charges: every acquirer operation ran exactly once
            assertThat(processor.executions("authorize:" + id)).as("authorize calls for %s", id).isEqualTo(1);
            List<UUID> refunds = jdbc.queryForList("SELECT id FROM refund WHERE payment_id = ?", UUID.class, paymentId);
            for (UUID refund : refunds) {
                assertThat(processor.executions("refund:" + refund)).as("acquirer calls for refund %s", refund).isEqualTo(1);
            }
            long refundRows = jdbc.queryForObject("SELECT coalesce(sum(amount_minor), 0) FROM refund WHERE payment_id = ?",
                    Long.class, paymentId);
            assertThat(refundRows).as("refund rows = running total, %s", id).isEqualTo(refunded);
            if (reference != null && captured > 0) {
                FakePaymentProcessor.Hold hold = processor.hold(reference);
                assertThat(hold.captured().amountMinor()).as("acquirer captured, %s", id).isEqualTo(captured);
                assertThat(hold.refunded().amountMinor()).as("acquirer refunded, %s", id).isEqualTo(refunded);
            }
            assertThat(refunded).isLessThanOrEqualTo(captured);

            // one event per change: no duplicates from retries
            assertThat(events(paymentId, "payment.captured")).isEqualTo(captured > 0 ? 1 : 0);
            assertThat(events(paymentId, "payment.refunded")).isEqualTo(refunds.size());
            assertThat(events(paymentId, "payment.authorized") + events(paymentId, "payment.failed")).isEqualTo(1);
        }

        // the ledger: the patient owes nothing, the system accounts moved by exactly the totals
        assertThat(ledger.balanceOf(ledger.patientReceivable(account)).isZero()).isTrue();
        Map<String, Money> after = systemBalances();
        assertThat(after.get("revenue").minus(before.get("revenue")).amountMinor()).isEqualTo(capturedTotal);
        assertThat(after.get("refunds").minus(before.get("refunds")).amountMinor()).isEqualTo(refundedTotal);
        assertThat(after.get("processor").minus(before.get("processor")).amountMinor()).isEqualTo(capturedTotal - refundedTotal);
        assertLedgerInvariants();
        assertThat(auditVerifier.verify().valid()).as("audit hash chain").isTrue();
    }

    /** The global ledger invariant: every entry balances per currency, and so does the whole ledger. */
    private void assertLedgerInvariants() {
        assertThat(jdbc.queryForList("""
                SELECT journal_entry_id FROM entry_line
                GROUP BY journal_entry_id, currency
                HAVING sum(CASE direction WHEN 'DEBIT' THEN amount_minor ELSE -amount_minor END) <> 0""", UUID.class))
                .as("unbalanced journal entries").isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT sum(CASE direction WHEN 'DEBIT' THEN amount_minor ELSE -amount_minor END)
                FROM entry_line GROUP BY currency""", Long.class)).as("trial balance per currency").containsOnly(0L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_entry je WHERE NOT EXISTS "
                + "(SELECT 1 FROM entry_line l WHERE l.journal_entry_id = je.id)", Long.class)).as("entries without lines").isZero();
    }

    private Map<String, Money> systemBalances() {
        return Map.of(
                "revenue", ledger.balanceOf(ledger.systemAccount(AccountPurpose.HOSPITAL_REVENUE, INR)),
                "refunds", ledger.balanceOf(ledger.systemAccount(AccountPurpose.REFUNDS, INR)),
                "processor", ledger.balanceOf(ledger.systemAccount(AccountPurpose.PROCESSOR_RECEIVABLE, INR)));
    }

    // ---- clients ----

    /** A client that keeps re-sending the same request while it is "in progress" (409 + Retry-After) or a 5xx. */
    private Answer client(String path, String key, String body) throws Exception {
        for (int attempt = 1; ; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json").header(IdempotencyFilter.HEADER, key)
                    .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            boolean inProgress = response.statusCode() == 409 && response.headers().firstValue("Retry-After").isPresent();
            if ((inProgress || response.statusCode() >= 500) && attempt < 200) {
                Thread.sleep(20);
                continue;
            }
            statuses.merge(response.statusCode(), 1, Integer::sum);
            return new Answer(response.statusCode(), response.body().isEmpty() ? json.createObjectNode() : json.readTree(response.body()));
        }
    }

    private <T> List<T> concurrently(List<Callable<T>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private String status(String id) {
        return jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, UUID.fromString(id));
    }

    private long minor(String id, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM payment WHERE id = ?", Long.class, UUID.fromString(id));
    }

    private long paymentsOf(UUID account) {
        return jdbc.queryForObject("SELECT count(*) FROM payment WHERE billing_account_id = ?", Long.class, account);
    }

    private long events(UUID paymentId, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = ?",
                Long.class, paymentId, type);
    }
}
