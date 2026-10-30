package org.pm.billingservice.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.TestcontainersConfiguration;
import org.pm.billingservice.ledger.AccountPurpose;
import org.pm.billingservice.ledger.LedgerAccount;
import org.pm.billingservice.ledger.LedgerService;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.processor.FakePaymentProcessor;
import org.pm.billingservice.service.BillingAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Refunds end to end: payment row, refund rows, fake processor and ledger must all agree.
 * Shared system accounts (refunds, processor receivable) are checked as deltas.
 */
@SpringBootTest(properties = "grpc.server.port=0") // same config as the other ITs -> Spring reuses one context
@Import(TestcontainersConfiguration.class)
class RefundIntegrationTest {

    private static final Currency INR = Currency.getInstance("INR");

    @Autowired private PaymentService paymentService;
    @Autowired private BillingAccountService billingAccountService;
    @Autowired private LedgerService ledgerService;
    @Autowired private FakePaymentProcessor processor;

    private UUID billingAccountId;
    private LedgerAccount patientReceivable;
    private LedgerAccount refundsAccount;
    private LedgerAccount processorReceivable;
    private Money refundsBefore;
    private Money processorReceivableBefore;

    @BeforeEach
    void setUp() {
        billingAccountId = billingAccountService
                .getOrCreateAccount(UUID.randomUUID(), "Test Patient", "patient@test.com").getId();
        patientReceivable = ledgerService.patientReceivable(billingAccountId);
        refundsAccount = ledgerService.systemAccount(AccountPurpose.REFUNDS, INR);
        processorReceivable = ledgerService.systemAccount(AccountPurpose.PROCESSOR_RECEIVABLE, INR);
        refundsBefore = ledgerService.balanceOf(refundsAccount);
        processorReceivableBefore = ledgerService.balanceOf(processorReceivable);
    }

    @Test
    void fullRefundUndoesTheMoneyButKeepsHistory() {
        Payment payment = captured(1_000_00, 1_000_00);

        Refund refund = paymentService.refund(newKey(), payment.getId(), null, "duplicate charge");

        assertThat(refund.getAmount()).isEqualTo(inr(1_000_00));
        assertThat(paymentService.get(payment.getId()).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(processor.hold(payment.getProcessorReference()).refunded()).isEqualTo(inr(1_000_00));
        // capture (+1000) and refund (-1000) cancel out at the acquirer; refunds shown separately from revenue
        assertThat(ledgerService.balanceOf(processorReceivable)).isEqualTo(processorReceivableBefore);
        assertThat(ledgerService.balanceOf(refundsAccount)).isEqualTo(refundsBefore.plus(inr(1_000_00)));
        assertThat(ledgerService.balanceOf(patientReceivable)).isEqualTo(inr(0));
    }

    @Test
    void severalPartialRefundsThenTheRest() {
        Payment payment = captured(1_000_00, 1_000_00);

        paymentService.refund(newKey(), payment.getId(), inr(300_00), "one item returned");
        paymentService.refund(newKey(), payment.getId(), inr(200_00), "second item returned");
        assertThat(paymentService.get(payment.getId()).getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);

        Refund rest = paymentService.refund(newKey(), payment.getId(), null, "cancelled"); // no amount = what's left

        assertThat(rest.getAmount()).isEqualTo(inr(500_00));
        assertThat(paymentService.get(payment.getId()).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(paymentService.refunds(payment.getId())).extracting(Refund::getAmount)
                .containsExactly(inr(300_00), inr(200_00), inr(500_00));
        assertThat(ledgerService.balanceOf(refundsAccount)).isEqualTo(refundsBefore.plus(inr(1_000_00)));
    }

    @Test
    void overRefundIsRejectedBeforeCallingProcessor() {
        Payment payment = captured(1_000_00, 1_000_00);
        paymentService.refund(newKey(), payment.getId(), inr(700_00), null);

        assertThatThrownBy(() -> paymentService.refund(newKey(), payment.getId(), inr(300_01), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only INR 300.00 is left");

        assertThat(processor.hold(payment.getProcessorReference()).refunded()).isEqualTo(inr(700_00));
        assertThat(ledgerService.balanceOf(refundsAccount)).isEqualTo(refundsBefore.plus(inr(700_00)));
    }

    @Test
    void refundIsCappedByCapturedNotAuthorized() {
        Payment payment = captured(1_000_00, 600_00); // partial capture

        assertThatThrownBy(() -> paymentService.refund(newKey(), payment.getId(), inr(600_01), null))
                .isInstanceOf(IllegalArgumentException.class);

        Refund all = paymentService.refund(newKey(), payment.getId(), null, null);
        assertThat(all.getAmount()).isEqualTo(inr(600_00));
    }

    @Test
    void refundBeforeCaptureIsStateError() {
        Payment payment = paymentService.authorize(newKey(), billingAccountId, inr(1_000_00), "pm_card_visa");

        assertThatThrownBy(() -> paymentService.refund(newKey(), payment.getId(), inr(100_00), null))
                .isInstanceOf(IllegalPaymentStateException.class);
    }

    @Test
    void refundAfterFullRefundIsStateError() {
        Payment payment = captured(1_000_00, 1_000_00);
        paymentService.refund(newKey(), payment.getId(), null, null);

        // no amount means "the rest", which is 0 - must still be a 409, not "amount must be positive"
        assertThatThrownBy(() -> paymentService.refund(newKey(), payment.getId(), null, null))
                .isInstanceOf(IllegalPaymentStateException.class);
    }

    @Test
    void refundsOfUnknownPaymentIsNotFound() {
        assertThatThrownBy(() -> paymentService.refunds(UUID.randomUUID())).isInstanceOf(PaymentNotFoundException.class);
    }

    /**
     * 8 refunds of ₹100 on one payment, all at once. All reach the processor; their DB writes collide on
     * @Version. Every one must still be recorded (retry), or the processor and our books would disagree.
     */
    @Test
    void concurrentPartialRefundsAreAllRecorded() throws Exception {
        Payment payment = captured(1_000_00, 1_000_00);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Refund>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return paymentService.refund(newKey(), payment.getId(), inr(100_00), "concurrent");
                }));
            }
            start.countDown();
            for (Future<Refund> result : results) {
                result.get(30, TimeUnit.SECONDS); // throws if any refund failed
            }
        } finally {
            pool.shutdownNow();
        }

        Payment after = paymentService.get(payment.getId());
        assertThat(after.getRefundedAmount()).isEqualTo(inr(800_00));
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(paymentService.refunds(payment.getId())).hasSize(threads);
        assertThat(processor.hold(payment.getProcessorReference()).refunded()).isEqualTo(inr(800_00));
        assertThat(ledgerService.balanceOf(refundsAccount)).isEqualTo(refundsBefore.plus(inr(800_00)));
    }

    private Payment captured(long authorizedPaise, long capturedPaise) {
        Payment payment = paymentService.authorize(newKey(), billingAccountId, inr(authorizedPaise), "pm_card_visa");
        return paymentService.capture(payment.getId(), inr(capturedPaise));
    }

    /** Each call is a new client request, so a new Idempotency-Key. */
    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static Money inr(long paise) {
        return Money.ofMinor(paise, "INR");
    }
}
