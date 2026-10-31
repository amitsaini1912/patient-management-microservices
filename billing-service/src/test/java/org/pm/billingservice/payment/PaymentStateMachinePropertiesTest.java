package org.pm.billingservice.payment;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;
import org.pm.billingservice.money.Money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pm.billingservice.payment.PaymentStatus.AUTHORIZED;
import static org.pm.billingservice.payment.PaymentStatus.CAPTURED;
import static org.pm.billingservice.payment.PaymentStatus.EXPIRED;
import static org.pm.billingservice.payment.PaymentStatus.FAILED;
import static org.pm.billingservice.payment.PaymentStatus.PARTIALLY_REFUNDED;
import static org.pm.billingservice.payment.PaymentStatus.REFUNDED;
import static org.pm.billingservice.payment.PaymentStatus.VOIDED;

/**
 * Day 21: ANY sequence of operations on a Payment - in any order, with any amounts, most of them illegal - keeps the
 * payment valid. After every step:
 *   - a refused operation changed nothing;
 *   - an accepted one moved the status along PaymentStateMachine (or kept it: partial refund, chargeback);
 *   - the amounts and the status satisfy exactly the CHECK constraints the database has (V3, V5, V15), so the
 *     Java side can never produce a row Postgres would reject.
 */
class PaymentStateMachinePropertiesTest {

    enum Kind { AUTHORIZE, FAIL, HOLD, APPROVE, REJECT, CAPTURE, VOID, EXPIRE, REFUND, CHARGE_BACK }

    /** amountMinor >= -5: that many minor units (some invalid); ALL / HALF: everything / half of what is left. */
    record Op(Kind kind, long amountMinor) {
        static final long ALL = -100, HALF = -200;
    }

    record Snapshot(PaymentStatus status, long captured, long refunded, long chargedBack, String failureReason,
                    String processorReference, Instant authorizedAt) {
        static Snapshot of(Payment p) {
            return new Snapshot(p.getStatus(), p.getCapturedAmount().amountMinor(), p.getRefundedAmount().amountMinor(),
                    p.getChargedBackAmount().amountMinor(), p.getFailureReason(), p.getProcessorReference(), p.getAuthorizedAt());
        }
    }

    @Provide
    Arbitrary<List<Op>> operations() {
        // random amounts rarely hit "exactly what is left" - the amount where full-refund / nothing-left rules
        // change (a break check showed a REFUNDED-status bug surviving). So "all" and "half" are drawn on purpose.
        Arbitrary<Long> amounts = Arbitraries.frequencyOf(
                Tuple.of(6, Arbitraries.longs().between(-5, 12_000)),
                Tuple.of(3, Arbitraries.just(Op.ALL)),
                Tuple.of(2, Arbitraries.just(Op.HALF)));
        Arbitrary<Op> op = Combinators.combine(Arbitraries.of(Kind.class), amounts).as(Op::new);
        return op.list().ofMinSize(1).ofMaxSize(25);
    }

    @Property(tries = 2000)
    void anySequenceKeepsThePaymentValid(@ForAll @LongRange(min = 1, max = 10_000) long amount,
                                         @ForAll("operations") List<Op> ops) {
        Payment payment = Payment.create(UUID.randomUUID(), UUID.randomUUID(), Money.ofMinor(amount, "INR"));
        check(payment);
        for (Op op : ops) {
            Snapshot before = Snapshot.of(payment);
            boolean accepted = apply(payment, op);
            Snapshot after = Snapshot.of(payment);
            if (!accepted) {
                assertThat(after).as("refused %s changed the payment", op).isEqualTo(before);
            } else if (after.status() != before.status() || op.kind() == Kind.REFUND) {
                assertThat(PaymentStateMachine.canMove(before.status(), after.status()))
                        .as("%s moved %s -> %s", op, before.status(), after.status()).isTrue();
            }
            check(payment);
        }
    }

    @Property
    void terminalPaymentsAcceptNothing(@ForAll("operations") @Size(min = 1) List<Op> ops) {
        Payment payment = Payment.create(UUID.randomUUID(), UUID.randomUUID(), Money.ofMinor(5_000, "INR"));
        payment.fail("card_declined");                                     // FAILED is terminal
        Snapshot failed = Snapshot.of(payment);
        for (Op op : ops) {
            assertThat(apply(payment, op)).as("%s on a FAILED payment", op).isFalse();
        }
        assertThat(Snapshot.of(payment)).isEqualTo(failed);
    }

    /** true = accepted; false = refused with one of the exceptions callers map to 409/400. */
    private static boolean apply(Payment payment, Op op) {
        long left = op.kind() == Kind.CAPTURE ? payment.getAmount().amountMinor() : payment.getRefundableAmount().amountMinor();
        long minor = op.amountMinor() == Op.ALL ? left : op.amountMinor() == Op.HALF ? Math.max(1, left / 2) : op.amountMinor();
        Money amount = new Money(minor, payment.getAmount().currency());
        try {
            switch (op.kind()) {
                case AUTHORIZE -> payment.authorize("auth_" + op.amountMinor());
                case FAIL -> payment.fail("card_declined");
                case HOLD -> payment.holdForReview();
                case APPROVE -> payment.review(Payment.ReviewDecision.APPROVED, "admin", null, Instant.now());
                case REJECT -> payment.review(Payment.ReviewDecision.REJECTED, "admin", null, Instant.now());
                case CAPTURE -> payment.capture(amount);
                case VOID -> payment.voidAuthorization();
                case EXPIRE -> payment.expire();
                case REFUND -> payment.refund(amount);
                case CHARGE_BACK -> payment.chargeBack(amount);
            }
            return true;
        } catch (IllegalPaymentStateException | IllegalArgumentException e) {
            return false;
        }
    }

    /** The database's CHECK constraints on payment, in Java. */
    private static void check(Payment p) {
        long amount = p.getAmount().amountMinor(), captured = p.getCapturedAmount().amountMinor();
        long refunded = p.getRefundedAmount().amountMinor(), chargedBack = p.getChargedBackAmount().amountMinor();
        PaymentStatus s = p.getStatus();
        assertThat(captured).as("payment_captured_within_authorized").isBetween(0L, amount);
        assertThat(refunded).as("payment_refunded_within_captured").isBetween(0L, captured);
        assertThat(chargedBack).as("charged_back_amount_minor >= 0").isGreaterThanOrEqualTo(0L);
        assertThat(refunded + chargedBack).as("payment_refunded_and_charged_back_within_captured").isLessThanOrEqualTo(captured);
        assertThat(captured > 0).as("payment_captured_matches_status (%s)", s)
                .isEqualTo(List.of(CAPTURED, PARTIALLY_REFUNDED, REFUNDED).contains(s));
        assertThat(refunded > 0).as("payment_refunded_matches_status (%s)", s)
                .isEqualTo(List.of(PARTIALLY_REFUNDED, REFUNDED).contains(s));
        assertThat(s == REFUNDED).as("payment_fully_refunded_matches_status")
                .isEqualTo(refunded > 0 && refunded == captured);
        assertThat(s == FAILED).as("payment_failure_reason_only_when_failed").isEqualTo(p.getFailureReason() != null);
        assertThat(p.getAuthorizedAt() != null).as("payment_authorized_at_matches_status (%s)", s)
                .isEqualTo(List.of(AUTHORIZED, CAPTURED, PARTIALLY_REFUNDED, REFUNDED, VOIDED, EXPIRED).contains(s));
        assertThat((p.getReviewDecision() == null) == (p.getReviewedAt() == null)).as("payment_review_complete").isTrue();
        assertThat(s == AUTHORIZED || s == CAPTURED || s == PARTIALLY_REFUNDED || s == REFUNDED || s == VOIDED || s == EXPIRED)
                .as("processor reference iff authorized at some point").isEqualTo(Objects.nonNull(p.getProcessorReference()));
    }
}
