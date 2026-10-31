package org.pm.billingservice.risk;

import java.util.List;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.payment.Payment;
import org.springframework.stereotype.Component;

/**
 * Billing's own rules when the risk engine can't be used (off, down, slow, circuit open). Payments don't stop
 * because risk is down (ADR 0017):
 *   - normal payments go through (fail OPEN): a hospital can't stop taking payments for a fraud-scoring outage;
 *   - big ones (and smaller foreign-currency ones) go to manual review (fail to REVIEW, not to decline);
 *   - never BLOCK: without the engine's history there is no evidence to decline on.
 */
@Component
public class FallbackRiskRules {

    private final RiskProperties.Fallback limits;

    public FallbackRiskRules(RiskProperties properties) {
        this.limits = properties.fallback();
    }

    public RiskVerdict decide(Payment payment, String fallbackReason) {
        Money booked = payment.getLockedRate().convert(payment.getAmount()); // compare in INR whatever the card paid in
        if (booked.amountMinor() >= limits.holdAtBookMinor()) {
            return RiskVerdict.fallback(RiskDecision.HOLD, List.of("FB_HIGH_AMOUNT"), fallbackReason);
        }
        if (payment.isForeignCurrency() && booked.amountMinor() >= limits.foreignHoldAtBookMinor()) {
            return RiskVerdict.fallback(RiskDecision.HOLD, List.of("FB_FOREIGN_HIGH_AMOUNT"), fallbackReason);
        }
        return RiskVerdict.fallback(RiskDecision.ALLOW, List.of("FB_DEFAULT"), fallbackReason);
    }
}
