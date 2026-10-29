package org.pm.billingservice.payment;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.pm.billingservice.payment.PaymentStatus.AUTHORIZED;
import static org.pm.billingservice.payment.PaymentStatus.CAPTURED;
import static org.pm.billingservice.payment.PaymentStatus.CREATED;
import static org.pm.billingservice.payment.PaymentStatus.EXPIRED;
import static org.pm.billingservice.payment.PaymentStatus.FAILED;
import static org.pm.billingservice.payment.PaymentStatus.PARTIALLY_REFUNDED;
import static org.pm.billingservice.payment.PaymentStatus.PENDING_REVIEW;
import static org.pm.billingservice.payment.PaymentStatus.REFUNDED;
import static org.pm.billingservice.payment.PaymentStatus.VOIDED;

/**
 * Every allowed status move, in one table. Anything not listed is illegal.
 * Reading this map should be enough to draw the state diagram.
 */
public final class PaymentStateMachine {

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = new EnumMap<>(PaymentStatus.class);

    static {
        ALLOWED.put(CREATED,            EnumSet.of(AUTHORIZED, PENDING_REVIEW, FAILED));
        ALLOWED.put(PENDING_REVIEW,     EnumSet.of(AUTHORIZED, FAILED));
        ALLOWED.put(AUTHORIZED,         EnumSet.of(CAPTURED, VOIDED, EXPIRED));
        ALLOWED.put(CAPTURED,           EnumSet.of(PARTIALLY_REFUNDED, REFUNDED));
        // a second partial refund keeps the status, so it is a move to itself
        ALLOWED.put(PARTIALLY_REFUNDED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED));
        // terminal: nothing can happen after these
        ALLOWED.put(REFUNDED,           EnumSet.noneOf(PaymentStatus.class));
        ALLOWED.put(FAILED,             EnumSet.noneOf(PaymentStatus.class));
        ALLOWED.put(VOIDED,             EnumSet.noneOf(PaymentStatus.class));
        ALLOWED.put(EXPIRED,            EnumSet.noneOf(PaymentStatus.class));
    }

    private PaymentStateMachine() {}

    public static Set<PaymentStatus> allowedFrom(PaymentStatus from) {
        return Collections.unmodifiableSet(ALLOWED.get(from));
    }

    public static boolean canMove(PaymentStatus from, PaymentStatus to) {
        return ALLOWED.get(from).contains(to);
    }

    static void requireMove(UUID paymentId, PaymentStatus from, PaymentStatus to) {
        if (!canMove(from, to)) {
            throw new IllegalPaymentStateException(paymentId, from, to);
        }
    }
}
