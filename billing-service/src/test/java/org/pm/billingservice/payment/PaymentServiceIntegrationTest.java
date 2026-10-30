package org.pm.billingservice.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.TestcontainersConfiguration;
import org.pm.billingservice.ledger.AccountPurpose;
import org.pm.billingservice.ledger.LedgerAccount;
import org.pm.billingservice.ledger.LedgerService;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.processor.FakePaymentProcessor;
import org.pm.billingservice.processor.FakePaymentProcessor.HoldState;
import org.pm.billingservice.service.BillingAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real Postgres, real ledger, FakePaymentProcessor. Every test checks three things agree:
 * the payment row, the "acquirer" (fake processor) and the ledger.
 * System accounts (revenue, processor receivable) are shared across tests, so they are checked as deltas.
 */
@SpringBootTest(properties = "grpc.server.port=0") // same config as the other ITs -> Spring reuses one context
@Import(TestcontainersConfiguration.class)
class PaymentServiceIntegrationTest {

    private static final Currency INR = Currency.getInstance("INR");
    private static final String CARD = "pm_card_visa";

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private BillingAccountService billingAccountService;
    @Autowired private LedgerService ledgerService;
    @Autowired private FakePaymentProcessor processor;
    @Autowired private JdbcTemplate jdbc;

    private UUID billingAccountId;
    private LedgerAccount patientReceivable;
    private LedgerAccount revenue;
    private LedgerAccount processorReceivable;
    private Money revenueBefore;
    private Money processorReceivableBefore;

    @BeforeEach
    void setUp() {
        billingAccountId = billingAccountService
                .getOrCreateAccount(UUID.randomUUID(), "Test Patient", "patient@test.com").getId();
        patientReceivable = ledgerService.patientReceivable(billingAccountId);
        revenue = ledgerService.systemAccount(AccountPurpose.HOSPITAL_REVENUE, INR);
        processorReceivable = ledgerService.systemAccount(AccountPurpose.PROCESSOR_RECEIVABLE, INR);
        revenueBefore = ledgerService.balanceOf(revenue);
        processorReceivableBefore = ledgerService.balanceOf(processorReceivable);
    }

    @Test
    void approvedAuthorizationHoldsMoneyButPostsNothing() {
        Payment payment = paymentService.authorize(newKey(), billingAccountId, inr(1_000_00), CARD);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(processor.hold(payment.getProcessorReference()).state()).isEqualTo(HoldState.AUTHORIZED);
        assertThat(processor.hold(payment.getProcessorReference()).authorized()).isEqualTo(inr(1_000_00));
        assertNoLedgerMovement();
    }

    @Test
    void declinedCardFailsWithReasonAndPostsNothing() {
        Payment payment = paymentService.authorize(newKey(), billingAccountId, inr(1_000_00), FakePaymentProcessor.DECLINED_TOKEN);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("card_declined");
        assertThat(payment.getProcessorReference()).isNull();
        assertNoLedgerMovement();
    }

    @Test
    void fullCapturePostsChargeAndCardPayment() {
        Payment payment = paymentService.authorize(newKey(), billingAccountId, inr(1_000_00), CARD);

        Payment captured = paymentService.capture(payment.getId(), null);

        assertThat(captured.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(captured.getCapturedAmount()).isEqualTo(inr(1_000_00));
        assertThat(processor.hold(payment.getProcessorReference()).state()).isEqualTo(HoldState.CAPTURED);
        // patient was charged and paid at once -> owes nothing; acquirer owes us; revenue earned
        assertThat(ledgerService.balanceOf(patientReceivable)).isEqualTo(inr(0));
        assertThat(ledgerService.balanceOf(processorReceivable)).isEqualTo(processorReceivableBefore.plus(inr(1_000_00)));
        assertThat(ledgerService.balanceOf(revenue)).isEqualTo(revenueBefore.plus(inr(1_000_00)));
        assertThat(linesOn(patientReceivable)).isEqualTo(2); // the charge and the payment, both visible
    }

    @Test
    void partialCapturePostsOnlyCapturedAmount() {
        Payment payment = paymentService.authorize(newKey(), billingAccountId, inr(1_000_00), CARD);

        Payment captured = paymentService.capture(payment.getId(), inr(600_00));

        assertThat(captured.getCapturedAmount()).isEqualTo(inr(600_00));
        assertThat(processor.hold(payment.getProcessorReference()).captured()).isEqualTo(inr(600_00));
        assertThat(ledgerService.balanceOf(revenue)).isEqualTo(revenueBefore.plus(inr(600_00)));
        assertThat(ledgerService.balanceOf(processorReceivable)).isEqualTo(processorReceivableBefore.plus(inr(600_00)));
    }

    @Test
    void captureAboveAuthorizedIsRejectedBeforeCallingProcessor() {
        Payment payment = paymentService.authorize(newKey(), billingAccountId, inr(1_000_00), CARD);

        assertThatThrownBy(() -> paymentService.capture(payment.getId(), inr(1_000_01)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(processor.hold(payment.getProcessorReference()).state()).isEqualTo(HoldState.AUTHORIZED);
        assertNoLedgerMovement();
    }

    @Test
    void voidReleasesHoldAndPostsNothing() {
        Payment payment = paymentService.authorize(newKey(), billingAccountId, inr(1_000_00), CARD);

        Payment voided = paymentService.voidAuthorization(payment.getId());

        assertThat(voided.getStatus()).isEqualTo(PaymentStatus.VOIDED);
        assertThat(processor.hold(payment.getProcessorReference()).state()).isEqualTo(HoldState.VOIDED);
        assertNoLedgerMovement();
    }

    @Test
    void captureAfterVoidIsStateErrorAndNeverReachesProcessor() {
        Payment payment = paymentService.authorize(newKey(), billingAccountId, inr(1_000_00), CARD);
        paymentService.voidAuthorization(payment.getId());

        // if the processor were called, the fake would throw PaymentProcessorException instead
        assertThatThrownBy(() -> paymentService.capture(payment.getId(), null))
                .isInstanceOf(IllegalPaymentStateException.class);
        assertNoLedgerMovement();
    }

    @Test
    void captureTwiceIsStateError() {
        Payment payment = paymentService.authorize(newKey(), billingAccountId, inr(1_000_00), CARD);
        paymentService.capture(payment.getId(), null);

        assertThatThrownBy(() -> paymentService.capture(payment.getId(), null))
                .isInstanceOf(IllegalPaymentStateException.class);
        assertThat(ledgerService.balanceOf(revenue)).isEqualTo(revenueBefore.plus(inr(1_000_00))); // posted once
    }

    @Test
    void voidOfDeclinedPaymentIsStateError() {
        Payment payment = paymentService.authorize(newKey(), billingAccountId, inr(1_000_00), FakePaymentProcessor.DECLINED_TOKEN);

        assertThatThrownBy(() -> paymentService.voidAuthorization(payment.getId()))
                .isInstanceOf(IllegalPaymentStateException.class);
    }

    @Test
    void unknownBillingAccountIsRejected() {
        assertThatThrownBy(() -> paymentService.authorize(newKey(), UUID.randomUUID(), inr(100), CARD))
                .isInstanceOf(UnknownBillingAccountException.class);
    }

    @Test
    void paymentInOtherCurrencyThanAccountIsRejected() {
        assertThatThrownBy(() -> paymentService.authorize(newKey(), billingAccountId, Money.ofMinor(100, "USD"), CARD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is in INR");
    }

    @Test
    void unknownPaymentIsNotFound() {
        assertThatThrownBy(() -> paymentService.capture(UUID.randomUUID(), null))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void wholeLedgerStillBalancesPerCurrency() {
        Payment payment = paymentService.authorize(newKey(), billingAccountId, inr(2_345_67), CARD);
        paymentService.capture(payment.getId(), inr(1_234_56));

        // trial balance: across ALL accounts, debits - credits = 0 in every currency
        List<Long> imbalances = jdbc.queryForList("""
                SELECT sum(CASE direction WHEN 'DEBIT' THEN amount_minor ELSE -amount_minor END)
                FROM entry_line GROUP BY currency""", Long.class);
        assertThat(imbalances).containsOnly(0L);
    }

    private void assertNoLedgerMovement() {
        assertThat(linesOn(patientReceivable)).isZero();
        assertThat(ledgerService.balanceOf(revenue)).isEqualTo(revenueBefore);
        assertThat(ledgerService.balanceOf(processorReceivable)).isEqualTo(processorReceivableBefore);
    }

    private int linesOn(LedgerAccount account) {
        return jdbc.queryForObject("SELECT count(*) FROM entry_line WHERE ledger_account_id = ?", Integer.class, account.getId());
    }

    /** Each call is a new client request, so a new Idempotency-Key. */
    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static Money inr(long paise) {
        return Money.ofMinor(paise, "INR");
    }
}
