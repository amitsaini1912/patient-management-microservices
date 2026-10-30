package org.pm.billingservice.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.TestcontainersConfiguration;
import org.pm.billingservice.ledger.LedgerService;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.processor.AuthorizationResult;
import org.pm.billingservice.processor.FakePaymentProcessor;
import org.pm.billingservice.service.BillingAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The crash the Idempotency-Key exists for: the processor call succeeded, then we died before
 * recording it (or before storing the response). The client retries with the same key and the
 * filter lets it through (key released or taken over). The retry must finish the job without
 * moving the money a second time.
 */
@SpringBootTest(properties = "grpc.server.port=0") // same config as the other ITs -> Spring reuses one context
@Import(TestcontainersConfiguration.class)
class PaymentRetryIntegrationTest {

    private static final String CARD = "pm_card_visa";

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private RefundRepository refundRepository;
    @Autowired private BillingAccountService billingAccountService;
    @Autowired private LedgerService ledgerService;
    @Autowired private FakePaymentProcessor processor;

    private UUID billingAccountId;

    @BeforeEach
    void setUp() {
        billingAccountId = billingAccountService
                .getOrCreateAccount(UUID.randomUUID(), "Test Patient", "patient@test.com").getId();
    }

    @Test
    void authorizeRetryAfterCrashBetweenProcessorAndDbReusesTheHold() {
        String key = newKey();
        UUID paymentId = PaymentService.paymentIdFor(key);
        // first attempt: saved CREATED, the processor placed the hold, then the process died
        paymentRepository.save(Payment.create(paymentId, billingAccountId, inr(1_000_00)));
        AuthorizationResult firstHold = processor.authorize("authorize:" + paymentId, paymentId, inr(1_000_00), CARD);

        Payment retried = paymentService.authorize(key, billingAccountId, inr(1_000_00), CARD);

        assertThat(retried.getId()).isEqualTo(paymentId);
        assertThat(retried.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(retried.getProcessorReference()).isEqualTo(firstHold.processorReference()); // the same hold
        assertThat(processor.executions("authorize:" + paymentId)).isEqualTo(1);
    }

    @Test
    void authorizeRetryAfterTheResponseWasLostReturnsThePayment() {
        String key = newKey();
        Payment first = paymentService.authorize(key, billingAccountId, inr(1_000_00), CARD);

        Payment again = paymentService.authorize(key, billingAccountId, inr(1_000_00), CARD);

        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(again.getProcessorReference()).isEqualTo(first.getProcessorReference());
        assertThat(processor.executions("authorize:" + first.getId())).isEqualTo(1);
    }

    @Test
    void refundRetryAfterCrashBetweenProcessorAndDbRefundsOnce() {
        Payment payment = captured();
        String key = newKey();
        UUID refundId = PaymentService.refundIdFor(key);
        // first attempt: the money went back to the card, then the process died before the DB write
        processor.refund("refund:" + refundId, payment.getProcessorReference(), inr(300_00));

        Refund refund = paymentService.refund(key, payment.getId(), inr(300_00), "item returned");

        assertThat(refund.getId()).isEqualTo(refundId);
        assertThat(processor.hold(payment.getProcessorReference()).refunded()).isEqualTo(inr(300_00)); // not 600
        assertThat(processor.executions("refund:" + refundId)).isEqualTo(1);
        assertThat(paymentService.get(payment.getId()).getRefundedAmount()).isEqualTo(inr(300_00));
        assertThat(ledgerService.balanceOf(ledgerService.patientReceivable(billingAccountId))).isEqualTo(inr(0));
    }

    @Test
    void refundRetryAfterTheResponseWasLostDoesNotRefundAgain() {
        Payment payment = captured();
        String key = newKey();
        Refund first = paymentService.refund(key, payment.getId(), null, null); // full refund

        // a full refund retried as "everything still refundable" would be 0 -> must return the first, not fail
        Refund again = paymentService.refund(key, payment.getId(), null, null);

        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(refundRepository.findByPaymentIdOrderByCreatedAt(payment.getId())).hasSize(1);
        assertThat(processor.hold(payment.getProcessorReference()).refunded()).isEqualTo(inr(1_000_00));
    }

    @Test
    void captureRetryAfterCrashBetweenProcessorAndDbCapturesOnce() {
        Payment payment = paymentService.authorize(newKey(), billingAccountId, inr(1_000_00), CARD);
        // first attempt: the processor captured, the DB write never happened
        processor.capture("capture:" + payment.getId() + ":100000:INR", payment.getProcessorReference(), inr(1_000_00));

        Payment captured = paymentService.capture(payment.getId(), null);

        assertThat(captured.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(processor.executions("capture:" + payment.getId() + ":100000:INR")).isEqualTo(1);
    }

    @Test
    void userVoidAndExpiryJobVoidAreOneProcessorCall() {
        Payment payment = paymentService.authorize(newKey(), billingAccountId, inr(1_000_00), CARD);
        // the expiry job released the hold at the processor, but the user's void is what we record
        processor.voidAuthorization(PaymentService.voidKey(payment.getId()), payment.getProcessorReference());

        Payment voided = paymentService.voidAuthorization(payment.getId());

        assertThat(voided.getStatus()).isEqualTo(PaymentStatus.VOIDED); // not a 502 "already voided"
        assertThat(processor.executions(PaymentService.voidKey(payment.getId()))).isEqualTo(1);
    }

    @Test
    void differentKeysAreDifferentPayments() {
        Payment a = paymentService.authorize(newKey(), billingAccountId, inr(1_000_00), CARD);
        Payment b = paymentService.authorize(newKey(), billingAccountId, inr(1_000_00), CARD);

        assertThat(a.getId()).isNotEqualTo(b.getId()); // two real purchases of the same amount are allowed
    }

    private Payment captured() {
        Payment payment = paymentService.authorize(newKey(), billingAccountId, inr(1_000_00), CARD);
        return paymentService.capture(payment.getId(), null);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static Money inr(long paise) {
        return Money.ofMinor(paise, "INR");
    }
}
