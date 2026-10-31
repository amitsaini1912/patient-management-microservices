package org.pm.billingservice.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.TestcontainersConfiguration;
import org.pm.billingservice.fx.FxRateService;
import org.pm.billingservice.ledger.AccountPurpose;
import org.pm.billingservice.ledger.LedgerService;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.service.BillingAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 19b: chargebacks (ledger loss + chargeback.created) and the payment.outcome events for the risk engine.
 * Same shared context as the other ITs (risk check off = fallback rules: INR 60,000 is held for review).
 */
@SpringBootTest(properties = "grpc.server.port=0")
@Import(TestcontainersConfiguration.class)
class ChargebackIntegrationTest {

    private static final Currency INR = Currency.getInstance("INR");
    private static final Currency USD = Currency.getInstance("USD");
    private static final String CARD = "pm_card_visa";

    @Autowired private PaymentService paymentService;
    @Autowired private BillingAccountService billingAccountService;
    @Autowired private LedgerService ledger;
    @Autowired private FxRateService rates;
    @Autowired private JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    private UUID accountId;

    @BeforeEach
    void setUp() {
        accountId = billingAccountService.getOrCreateAccount(UUID.randomUUID(), "Disputed Patient", "cb@test.com").getId();
    }

    private Payment captured(Money amount) {
        Payment payment = paymentService.authorize(UUID.randomUUID().toString(), accountId, amount, CARD);
        return paymentService.capture(payment.getId(), null);
    }

    private Money balance(AccountPurpose purpose, Currency currency) {
        return ledger.balanceOf(ledger.systemAccount(purpose, currency));
    }

    private List<JsonNode> jsonEvents(Payment payment, String topic) {
        return jdbc.queryForList("SELECT payload FROM outbox_event WHERE aggregate_id = ? AND topic = ? ORDER BY created_at",
                byte[].class, payment.getId(), topic).stream().map(bytes -> {
                    try {
                        return json.readTree(bytes);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).toList();
    }

    @Test
    void aChargebackIsALossTheAcquirerTakesFromWhatItOwesUs() {
        Payment payment = captured(Money.ofMinor(5_000_00, "INR"));
        Money lossesBefore = balance(AccountPurpose.CHARGEBACKS, INR);
        Money processorBefore = balance(AccountPurpose.PROCESSOR_RECEIVABLE, INR);
        Money revenueBefore = balance(AccountPurpose.HOSPITAL_REVENUE, INR);

        Chargeback chargeback = paymentService.chargeBack(UUID.randomUUID().toString(), payment.getId(), null, "fraud", "CASE-1");

        assertThat(chargeback.getAmount()).isEqualTo(Money.ofMinor(5_000_00, "INR"));       // no amount = all of it
        assertThat(balance(AccountPurpose.CHARGEBACKS, INR).minus(lossesBefore)).isEqualTo(Money.ofMinor(5_000_00, "INR"));
        assertThat(processorBefore.minus(balance(AccountPurpose.PROCESSOR_RECEIVABLE, INR))).isEqualTo(Money.ofMinor(5_000_00, "INR"));
        assertThat(balance(AccountPurpose.HOSPITAL_REVENUE, INR)).isEqualTo(revenueBefore);  // revenue untouched
        Payment after = paymentService.get(payment.getId());
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(after.getChargedBackAmount()).isEqualTo(Money.ofMinor(5_000_00, "INR"));
        assertThat(after.getRefundableAmount().isZero()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_entry WHERE source_type = 'CHARGEBACK' AND source_id = ?",
                Integer.class, chargeback.getId())).isEqualTo(1);
    }

    @Test
    void chargebackCreatedFollowsTheRiskEnginesContract() {
        Payment payment = captured(Money.ofMinor(2_500_00, "INR"));

        Chargeback chargeback = paymentService.chargeBack(UUID.randomUUID().toString(), payment.getId(),
                Money.ofMinor(1_000_00, "INR"), "product_not_received", null);

        List<JsonNode> events = jsonEvents(payment, "chargeback.created");
        assertThat(events).hasSize(1);
        JsonNode event = events.get(0);
        // razorpay-risk-agent/docs/contracts/chargeback.created.v1.schema.json: exactly these 7 fields
        assertThat(event.size()).isEqualTo(7);
        assertThat(event.get("transaction_id").asText()).isEqualTo(payment.getId().toString());
        assertThat(event.get("amount_minor").isIntegralNumber()).isTrue();
        assertThat(event.get("amount_minor").asLong()).isEqualTo(1_000_00);
        assertThat(event.get("currency").asText()).isEqualTo("INR");
        assertThat(event.get("reason").asText()).isEqualTo("product_not_received");
        assertThat(event.get("schema_version").asInt()).isEqualTo(1);
        assertThat(event.get("created_at").asText()).isEqualTo(chargeback.getCreatedAt().truncatedTo(ChronoUnit.MILLIS).toString());
        assertThat(UUID.fromString(event.get("event_id").asText())).isNotNull();
        assertThat(jdbc.queryForObject("SELECT message_key FROM outbox_event WHERE topic = 'chargeback.created' AND aggregate_id = ?",
                String.class, payment.getId())).isEqualTo(payment.getId().toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'payment.charged_back' AND entity_id = ?",
                Integer.class, payment.getId().toString())).isEqualTo(1);
    }

    @Test
    void aRetryWithTheSameKeyIsOneChargeback() {
        Payment payment = captured(Money.ofMinor(3_000_00, "INR"));
        String key = UUID.randomUUID().toString();

        Chargeback first = paymentService.chargeBack(key, payment.getId(), Money.ofMinor(1_000_00, "INR"), "fraud", null);
        Chargeback again = paymentService.chargeBack(key, payment.getId(), Money.ofMinor(1_000_00, "INR"), "fraud", null);

        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(paymentService.chargebacks(payment.getId())).hasSize(1);
        assertThat(paymentService.get(payment.getId()).getChargedBackAmount()).isEqualTo(Money.ofMinor(1_000_00, "INR"));
        assertThat(jsonEvents(payment, "chargeback.created")).hasSize(1);
    }

    @Test
    void chargebacksAndRefundsShareWhatIsLeft() {
        Payment payment = captured(Money.ofMinor(10_000_00, "INR"));
        paymentService.chargeBack(UUID.randomUUID().toString(), payment.getId(), Money.ofMinor(4_000_00, "INR"), "fraud", null);

        assertThatThrownBy(() -> paymentService.refund(UUID.randomUUID().toString(), payment.getId(), Money.ofMinor(6_000_01, "INR"), null))
                .isInstanceOf(IllegalArgumentException.class);
        paymentService.refund(UUID.randomUUID().toString(), payment.getId(), null, "rest");   // refunds the 6,000 left
        Payment after = paymentService.get(payment.getId());
        assertThat(after.getRefundedAmount()).isEqualTo(Money.ofMinor(6_000_00, "INR"));
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);   // REFUNDED = we refunded ALL of it
        assertThat(after.getRefundableAmount().isZero()).isTrue();
        assertThatThrownBy(() -> paymentService.chargeBack(UUID.randomUUID().toString(), payment.getId(), null, "fraud", null))
                .isInstanceOf(IllegalArgumentException.class);                              // nothing held any more
        Payment full = captured(Money.ofMinor(1_000_00, "INR"));
        paymentService.refund(UUID.randomUUID().toString(), full.getId(), null, "all");
        assertThatThrownBy(() -> paymentService.chargeBack(UUID.randomUUID().toString(), full.getId(), null, "fraud", null))
                .isInstanceOf(IllegalPaymentStateException.class);                          // REFUNDED: 409
    }

    @Test
    void onlyMoneyWeHoldCanBeChargedBack() {
        Payment authorized = paymentService.authorize(UUID.randomUUID().toString(), accountId, Money.ofMinor(1_000_00, "INR"), CARD);
        assertThatThrownBy(() -> paymentService.chargeBack(UUID.randomUUID().toString(), authorized.getId(), null, "fraud", null))
                .isInstanceOf(IllegalPaymentStateException.class);
        Payment payment = captured(Money.ofMinor(1_000_00, "INR"));
        assertThatThrownBy(() -> paymentService.chargeBack(UUID.randomUUID().toString(), payment.getId(),
                Money.ofMinor(1_000_01, "INR"), "fraud", null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(paymentService.chargebacks(payment.getId())).isEmpty();
    }

    @Test
    void aForeignChargebackIsBookedAtTodaysRate() {
        rates.record(USD, INR, new BigDecimal("83.00"), "test", null);
        Payment payment = captured(Money.ofMinor(100_00, "USD"));
        rates.record(USD, INR, new BigDecimal("85.00"), "test", null);                     // the rate moves
        Money lossesBefore = balance(AccountPurpose.CHARGEBACKS, INR);
        Money processorUsdBefore = balance(AccountPurpose.PROCESSOR_RECEIVABLE, USD);

        paymentService.chargeBack(UUID.randomUUID().toString(), payment.getId(), Money.ofMinor(50_00, "USD"), "fraud", null);

        assertThat(balance(AccountPurpose.CHARGEBACKS, INR).minus(lossesBefore)).isEqualTo(Money.ofMinor(4_250_00, "INR"));
        assertThat(processorUsdBefore.minus(balance(AccountPurpose.PROCESSOR_RECEIVABLE, USD))).isEqualTo(Money.ofMinor(50_00, "USD"));
    }

    // ---- payment.outcome: what happened to each payment, for the risk engine's labels ----

    @Test
    void captureSendsACapturedOutcome() {
        Payment payment = captured(Money.ofMinor(1_200_00, "INR"));

        List<JsonNode> outcomes = jsonEvents(payment, "payment.outcome");
        assertThat(outcomes).hasSize(1);
        JsonNode outcome = outcomes.get(0);
        assertThat(outcome.size()).isEqualTo(7);
        assertThat(outcome.get("outcome").asText()).isEqualTo("captured");
        assertThat(outcome.get("transaction_id").asText()).isEqualTo(payment.getId().toString());
        assertThat(outcome.get("amount_minor").asLong()).isEqualTo(1_200_00);
        assertThat(outcome.get("schema_version").asInt()).isEqualTo(1);
        assertThat(Instant.parse(outcome.get("occurred_at").asText()).getNano() % 1_000_000).isZero();   // ms precision
    }

    @Test
    void reviewsSendTheirOutcome() {
        // risk check off -> fallback rules: INR 60,000 goes to review
        Payment approved = paymentService.authorize(UUID.randomUUID().toString(), accountId, Money.ofMinor(60_000_00, "INR"), CARD);
        Payment rejected = paymentService.authorize(UUID.randomUUID().toString(), accountId, Money.ofMinor(60_000_00, "INR"), CARD);
        assertThat(approved.getStatus()).isEqualTo(PaymentStatus.PENDING_REVIEW);

        paymentService.review(approved.getId(), Payment.ReviewDecision.APPROVED, "admin@test.com", null);
        paymentService.capture(approved.getId(), null);
        paymentService.review(rejected.getId(), Payment.ReviewDecision.REJECTED, "admin@test.com", null);

        assertThat(jsonEvents(approved, "payment.outcome")).extracting(e -> e.get("outcome").asText())
                .containsExactly("review_approved", "captured");
        assertThat(jsonEvents(rejected, "payment.outcome")).extracting(e -> e.get("outcome").asText())
                .containsExactly("review_rejected");
    }
}
