package org.pm.billingservice.reconciliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.TestcontainersConfiguration;
import org.pm.billingservice.ledger.AccountPurpose;
import org.pm.billingservice.ledger.EntrySource;
import org.pm.billingservice.ledger.JournalEntry;
import org.pm.billingservice.ledger.LedgerService;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.payment.Payment;
import org.pm.billingservice.payment.PaymentService;
import org.pm.billingservice.payment.Refund;
import org.pm.billingservice.service.BillingAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Our real ledger (payments captured/refunded through PaymentService with the fake acquirer) against a seeded
 * settlement report. Other tests also capture "today", so assertions look only at this test's references.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "grpc.server.port=0")
@Import(TestcontainersConfiguration.class)
class ReconciliationIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private PaymentService paymentService;
    @Autowired private BillingAccountService billingAccountService;
    @Autowired private LedgerService ledgerService;
    @Autowired private FakeSettlementSource settlement;
    @Autowired private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();
    private final LocalDate today = LocalDate.now(ZoneOffset.UTC);

    @Test
    void oneOfEachDifferenceIsClassifiedAndMatchesAreNotListed() throws Exception {
        UUID account = account();
        Instant now = Instant.now();

        Payment matched = captured(account, 1_000_00);
        settle("cap_1", "CAPTURE", matched.getId(), 1_000_00, "INR", now);
        Refund refund = paymentService.refund(newKey(), matched.getId(), inr(200_00), "returned");
        settle(refund.getProcessorReference(), "REFUND", matched.getId(), 200_00, "INR", now);      // matched refund

        Payment shortPaid = captured(account, 500_00);
        settle("cap_2", "CAPTURE", shortPaid.getId(), 499_00, "INR", now);                          // AMOUNT_MISMATCH

        Payment wrongCurrency = captured(account, 700_00);
        settle("cap_3", "CAPTURE", wrongCurrency.getId(), 700_00, "USD", now);                      // CURRENCY_MISMATCH

        Payment acquirerLostIt = captured(account, 300_00);                                       // MISSING_IN_SETTLEMENT

        UUID neverRecorded = UUID.randomUUID();
        settle("cap_4", "CAPTURE", neverRecorded, 250_00, "INR", now);                              // MISSING_IN_LEDGER
        settle("re_we_lost", "REFUND", matched.getId(), 100_00, "INR", now);                        // MISSING_IN_LEDGER (refund)

        JsonNode run = json(post(today));

        assertThat(run.get("status").asText()).isEqualTo("COMPLETED");
        Map<String, String> mine = classified(run, List.of(matched.getId().toString(), refund.getProcessorReference(),
                shortPaid.getId().toString(), wrongCurrency.getId().toString(), acquirerLostIt.getId().toString(),
                neverRecorded.toString(), "re_we_lost"));
        assertThat(mine).containsExactlyInAnyOrderEntriesOf(Map.of(
                shortPaid.getId().toString(), "AMOUNT_MISMATCH",
                wrongCurrency.getId().toString(), "CURRENCY_MISMATCH",
                acquirerLostIt.getId().toString(), "MISSING_IN_SETTLEMENT",
                neverRecorded.toString(), "MISSING_IN_LEDGER",
                "re_we_lost", "MISSING_IN_LEDGER"));                      // the matched capture + refund aren't listed
        JsonNode amount = item(run, shortPaid.getId().toString());
        assertThat(amount.get("ledgerAmountMinor").asLong()).isEqualTo(500_00);
        assertThat(amount.get("settlementAmountMinor").asLong()).isEqualTo(499_00);
        assertThat(amount.get("settlementTransactionId").asText()).isEqualTo("cap_2");
        assertThat(item(run, "re_we_lost").get("transactionType").asText()).isEqualTo("REFUND");

        // the same result is stored and readable
        HttpResponse<String> stored = http.send(HttpRequest.newBuilder(uri("/reconciliation/runs/" + today)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(stored.statusCode()).isEqualTo(200);
        assertThat(classified(json(stored), new ArrayList<>(mine.keySet()))).isEqualTo(mine);
    }

    @Test
    void aCaptureBookedAcrossMidnightIsMatchedNotMissingOnBothDays() throws Exception {
        // we posted it today; the acquirer booked it at 23:59:59 yesterday (clocks / cut-off differ by a moment)
        Payment payment = captured(account(), 400_00);
        Instant lastSecondOfYesterday = today.atStartOfDay(ZoneOffset.UTC).toInstant().minusSeconds(1);
        settle("cap_midnight", "CAPTURE", payment.getId(), 400_00, "INR", lastSecondOfYesterday);

        JsonNode todayRun = json(post(today));
        JsonNode yesterdayRun = json(post(today.minusDays(1)));

        assertThat(classified(todayRun, List.of(payment.getId().toString()))).isEmpty();
        assertThat(classified(yesterdayRun, List.of(payment.getId().toString()))).isEmpty();
    }

    @Test
    void reportUnavailableIsAFailedRunAnd502() throws Exception {
        settlement.failWith(new IllegalStateException("acquirer down"));
        try {
            HttpResponse<String> response = post(today.minusDays(3));

            assertThat(response.statusCode()).isEqualTo(502);
            assertThat(json(response).get("status").asText()).isEqualTo("FAILED");
            assertThat(json(response).get("error").asText()).contains("acquirer down");
        } finally {
            settlement.failWith(null);
        }
        // a re-run once the report is back replaces it as "the latest"
        assertThat(json(post(today.minusDays(3))).get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void futureDayIs400UnknownDayIs404() throws Exception {
        assertThat(post(today.plusDays(1)).statusCode()).isEqualTo(400);
        assertThat(http.send(HttpRequest.newBuilder(uri("/reconciliation/runs/2001-01-01")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
    }

    @Test
    void theLedgerRefusesToPostTheSameCaptureTwice() {
        // DB guarantee behind the matching: one CAPTURE entry per payment (unique source), whatever the code does
        Payment payment = captured(account(), 100_00);
        var receivable = ledgerService.systemAccount(AccountPurpose.PROCESSOR_RECEIVABLE, payment.getAmount().currency());
        var revenue = ledgerService.systemAccount(AccountPurpose.HOSPITAL_REVENUE, payment.getAmount().currency());

        assertThatThrownBy(() -> ledgerService.post(JournalEntry.draft("capture again")
                .source(EntrySource.CAPTURE, payment.getId())
                .debit(receivable, inr(100_00))
                .credit(revenue, inr(100_00))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- helpers ----

    private UUID account() {
        return billingAccountService.getOrCreateAccount(UUID.randomUUID(), "Test Patient", "patient@test.com").getId();
    }

    private Payment captured(UUID account, long paise) {
        Payment payment = paymentService.authorize(newKey(), account, inr(paise), "pm_card_visa");
        return paymentService.capture(payment.getId(), null);
    }

    private void settle(String id, String type, UUID paymentId, long amountMinor, String currency, Instant at) {
        settlement.add(new SettlementRow(id, type, "auth_x", paymentId.toString(), amountMinor, currency, at));
    }

    private HttpResponse<String> post(LocalDate date) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/reconciliation/runs?date=" + date))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** reference -> classification, for the given references only (other tests add noise to "today"). */
    private static Map<String, String> classified(JsonNode run, List<String> references) {
        List<JsonNode> items = new ArrayList<>();
        run.get("items").forEach(items::add);
        return items.stream().filter(i -> references.contains(i.get("reference").asText()))
                .collect(Collectors.toMap(i -> i.get("reference").asText(), i -> i.get("classification").asText()));
    }

    private static JsonNode item(JsonNode run, String reference) {
        for (JsonNode i : run.get("items")) {
            if (reference.equals(i.get("reference").asText())) {
                return i;
            }
        }
        throw new AssertionError("no item " + reference);
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static Money inr(long paise) {
        return Money.ofMinor(paise, "INR");
    }
}
