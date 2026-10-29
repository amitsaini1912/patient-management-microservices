package org.pm.billingservice.payment;

import org.junit.jupiter.api.Test;
import org.pm.billingservice.money.CurrencyMismatchException;
import org.pm.billingservice.money.Money;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The entity's own rules (amounts + state). No database. */
class PaymentTest {

    @Test
    void newPaymentIsCreated() {
        Payment payment = newPayment(1_000_00);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(payment.getCapturedAmount()).isEqualTo(inr(0));
        assertThat(payment.getVersion()).isNull(); // not saved yet
    }

    @Test
    void amountMustBePositive() {
        assertThatThrownBy(() -> newPayment(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fullCapture() {
        Payment payment = authorized(1_000_00);

        payment.capture(inr(1_000_00));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(payment.getCapturedAmount()).isEqualTo(inr(1_000_00));
    }

    @Test
    void partialCaptureIsCapturedWithSmallerAmount() {
        Payment payment = authorized(1_000_00);

        payment.capture(inr(600_00));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(payment.getCapturedAmount()).isEqualTo(inr(600_00));
        assertThat(payment.getRefundableAmount()).isEqualTo(inr(600_00));
    }

    @Test
    void cannotCaptureMoreThanAuthorized() {
        Payment payment = authorized(1_000_00);

        assertThatThrownBy(() -> payment.capture(inr(1_000_01))).isInstanceOf(IllegalArgumentException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED); // nothing changed
    }

    @Test
    void cannotCaptureBeforeAuthorization() {
        Payment payment = newPayment(1_000_00);

        assertThatThrownBy(() -> payment.capture(inr(1_000_00)))
                .isInstanceOf(IllegalPaymentStateException.class)
                .hasMessageContaining("cannot move from CREATED to CAPTURED");
    }

    @Test
    void cannotCaptureTwice() {
        Payment payment = authorized(1_000_00);
        payment.capture(inr(400_00));

        assertThatThrownBy(() -> payment.capture(inr(400_00))).isInstanceOf(IllegalPaymentStateException.class);
    }

    @Test
    void captureInOtherCurrencyIsRejected() {
        Payment payment = authorized(1_000_00);

        assertThatThrownBy(() -> payment.capture(Money.ofMinor(100, "USD")))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void partialRefundsThenFullRefund() {
        Payment payment = authorized(1_000_00);
        payment.capture(inr(1_000_00));

        payment.refund(inr(300_00));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);

        payment.refund(inr(200_00));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(payment.getRefundableAmount()).isEqualTo(inr(500_00));

        payment.refund(inr(500_00));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundedAmount()).isEqualTo(inr(1_000_00));
    }

    @Test
    void refundIsLimitedToCapturedNotAuthorized() {
        Payment payment = authorized(1_000_00);
        payment.capture(inr(600_00)); // partial capture: only 600 was ever taken

        assertThatThrownBy(() -> payment.refund(inr(600_01)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only INR 600.00 is left");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    void refundBeforeCaptureIsStateError() {
        Payment payment = authorized(1_000_00);

        assertThatThrownBy(() -> payment.refund(inr(100_00))).isInstanceOf(IllegalPaymentStateException.class);
    }

    @Test
    void nothingAfterFullRefund() {
        Payment payment = authorized(1_000_00);
        payment.capture(inr(1_000_00));
        payment.refund(inr(1_000_00));

        assertThatThrownBy(() -> payment.refund(inr(1))).isInstanceOf(IllegalPaymentStateException.class);
    }

    @Test
    void voidAfterCaptureIsRefused() {
        Payment payment = authorized(1_000_00);
        payment.capture(inr(1_000_00));

        // once money has moved, the only way back is a refund
        assertThatThrownBy(payment::voidAuthorization).isInstanceOf(IllegalPaymentStateException.class);
    }

    @Test
    void reviewThenAuthorize() {
        Payment payment = newPayment(1_000_00);

        payment.holdForReview();
        payment.authorize("auth_test");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
    }

    @Test
    void failRecordsReason() {
        Payment payment = newPayment(1_000_00);

        payment.fail("issuer declined: insufficient funds");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("issuer declined: insufficient funds");
        assertThatThrownBy(() -> payment.authorize("auth_test")).isInstanceOf(IllegalPaymentStateException.class);
    }

    @Test
    void expiredAuthorizationCannotBeCaptured() {
        Payment payment = authorized(1_000_00);
        payment.expire();

        assertThatThrownBy(() -> payment.capture(inr(1_000_00))).isInstanceOf(IllegalPaymentStateException.class);
    }

    private static Payment newPayment(long paise) {
        return Payment.create(UUID.randomUUID(), inr(paise));
    }

    private static Payment authorized(long paise) {
        Payment payment = newPayment(paise);
        payment.authorize("auth_test");
        return payment;
    }

    private static Money inr(long paise) {
        return Money.ofMinor(paise, "INR");
    }
}
