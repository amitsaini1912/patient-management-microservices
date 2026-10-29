package org.pm.billingservice.processor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.pm.billingservice.money.Money;
import org.springframework.stereotype.Component;

/**
 * In-memory acquirer for local runs and tests. Behaves like a real one in the ways that matter:
 * it remembers each authorization, refuses to capture more than was held, and refuses to
 * capture or void twice.
 *
 * Test tokens (same idea as Stripe's test cards):
 *   pm_card_declined           -> declined "card_declined"
 *   pm_card_insufficient_funds -> declined "insufficient_funds"
 *   anything else              -> approved
 */
@Component
public class FakePaymentProcessor implements PaymentProcessor {

    public static final String DECLINED_TOKEN = "pm_card_declined";
    public static final String INSUFFICIENT_FUNDS_TOKEN = "pm_card_insufficient_funds";

    public enum HoldState { AUTHORIZED, CAPTURED, VOIDED }

    public record Hold(Money authorized, Money captured, Money refunded, HoldState state) {}

    private final Map<String, Hold> holds = new ConcurrentHashMap<>();

    @Override
    public AuthorizationResult authorize(UUID paymentId, Money amount, String paymentMethodToken) {
        if (DECLINED_TOKEN.equals(paymentMethodToken)) {
            return AuthorizationResult.declined("card_declined");
        }
        if (INSUFFICIENT_FUNDS_TOKEN.equals(paymentMethodToken)) {
            return AuthorizationResult.declined("insufficient_funds");
        }
        String reference = "auth_" + UUID.randomUUID();
        Money zero = Money.zero(amount.currency());
        holds.put(reference, new Hold(amount, zero, zero, HoldState.AUTHORIZED));
        return AuthorizationResult.approved(reference);
    }

    @Override
    public void capture(String processorReference, Money amount) {
        // compute() runs atomically per key, so two captures of one hold can't both pass
        holds.compute(processorReference, (reference, hold) -> {
            requireAuthorized(reference, hold);
            if (amount.isGreaterThan(hold.authorized())) {
                throw new PaymentProcessorException("capture " + amount + " exceeds hold " + hold.authorized());
            }
            return new Hold(hold.authorized(), amount, hold.refunded(), HoldState.CAPTURED);
        });
    }

    @Override
    public void voidAuthorization(String processorReference) {
        holds.compute(processorReference, (reference, hold) -> {
            requireAuthorized(reference, hold);
            return new Hold(hold.authorized(), hold.captured(), hold.refunded(), HoldState.VOIDED);
        });
    }

    @Override
    public String refund(String processorReference, Money amount) {
        holds.compute(processorReference, (reference, hold) -> {
            if (hold == null || hold.state() != HoldState.CAPTURED) {
                throw new PaymentProcessorException("authorization " + reference + " has nothing captured to refund");
            }
            Money refundedAfter = hold.refunded().plus(amount);
            if (refundedAfter.isGreaterThan(hold.captured())) {
                throw new PaymentProcessorException("refund " + amount + " exceeds what is left of " + hold.captured());
            }
            return new Hold(hold.authorized(), hold.captured(), refundedAfter, HoldState.CAPTURED);
        });
        return "re_" + UUID.randomUUID();
    }

    /** For tests: what the "acquirer" thinks happened. */
    public Hold hold(String processorReference) {
        return holds.get(processorReference);
    }

    private static void requireAuthorized(String reference, Hold hold) {
        if (hold == null) {
            throw new PaymentProcessorException("unknown authorization " + reference);
        }
        if (hold.state() != HoldState.AUTHORIZED) {
            throw new PaymentProcessorException("authorization " + reference + " is already " + hold.state());
        }
    }
}
