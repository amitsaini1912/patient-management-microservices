package org.pm.billingservice.processor;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.pm.billingservice.money.Money;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 *
 * Idempotent like a real acquirer: a call repeated with the same idempotency key returns the
 * first call's result and does nothing again. Failed calls are not remembered (they can be retried).
 */
@Component
@ConditionalOnProperty(name = "billing.processor", havingValue = "fake") // tests; see SimulatorPaymentProcessor
public class FakePaymentProcessor implements PaymentProcessor {

    public static final String DECLINED_TOKEN = "pm_card_declined";
    public static final String INSUFFICIENT_FUNDS_TOKEN = "pm_card_insufficient_funds";

    public enum HoldState { AUTHORIZED, CAPTURED, VOIDED }

    public record Hold(Money authorized, Money captured, Money refunded, HoldState state) {}

    private final Map<String, Hold> holds = new ConcurrentHashMap<>();
    // idempotency key -> result of the first successful call with that key
    private final Map<String, Object> completedCalls = new ConcurrentHashMap<>();
    private final Map<String, Integer> executions = new ConcurrentHashMap<>();
    // a real acquirer takes 100s of ms; tests set this so concurrent requests really overlap
    private volatile Duration latency = Duration.ZERO;

    @Override
    public AuthorizationResult authorize(String idempotencyKey, UUID paymentId, Money amount, String paymentMethodToken) {
        return once(idempotencyKey, () -> doAuthorize(amount, paymentMethodToken));
    }

    private AuthorizationResult doAuthorize(Money amount, String paymentMethodToken) {
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
    public void capture(String idempotencyKey, String processorReference, Money amount) {
        once(idempotencyKey, () -> doCapture(processorReference, amount));
    }

    private Boolean doCapture(String processorReference, Money amount) {
        // compute() runs atomically per key, so two captures of one hold can't both pass
        holds.compute(processorReference, (reference, hold) -> {
            requireAuthorized(reference, hold);
            if (amount.isGreaterThan(hold.authorized())) {
                throw new PaymentProcessorException("capture " + amount + " exceeds hold " + hold.authorized());
            }
            return new Hold(hold.authorized(), amount, hold.refunded(), HoldState.CAPTURED);
        });
        return Boolean.TRUE;
    }

    @Override
    public void voidAuthorization(String idempotencyKey, String processorReference) {
        once(idempotencyKey, () -> doVoid(processorReference));
    }

    private Boolean doVoid(String processorReference) {
        holds.compute(processorReference, (reference, hold) -> {
            requireAuthorized(reference, hold);
            return new Hold(hold.authorized(), hold.captured(), hold.refunded(), HoldState.VOIDED);
        });
        return Boolean.TRUE;
    }

    @Override
    public String refund(String idempotencyKey, String processorReference, Money amount) {
        return once(idempotencyKey, () -> doRefund(processorReference, amount));
    }

    private String doRefund(String processorReference, Money amount) {
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

    /**
     * Runs {@code call} only for the first successful use of {@code idempotencyKey}.
     * computeIfAbsent is atomic per key: concurrent calls with one key run it once and all get its result.
     * If {@code call} throws, nothing is stored, so a retry with the same key runs it again.
     */
    @SuppressWarnings("unchecked")
    private <T> T once(String idempotencyKey, Supplier<T> call) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new PaymentProcessorException("idempotency key is required");
        }
        return (T) completedCalls.computeIfAbsent(idempotencyKey, k -> {
            sleep(latency);
            T result = call.get();
            executions.merge(k, 1, Integer::sum);
            return result;
        });
    }

    /** For tests: make every call take this long. Reset to {@link Duration#ZERO} afterwards. */
    public void setLatency(Duration latency) {
        this.latency = latency;
    }

    private static void sleep(Duration duration) {
        if (duration.isZero()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaymentProcessorException("interrupted");
        }
    }

    /** For tests: how many times the work behind this key really ran (must never exceed 1). */
    public int executions(String idempotencyKey) {
        return executions.getOrDefault(idempotencyKey, 0);
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
