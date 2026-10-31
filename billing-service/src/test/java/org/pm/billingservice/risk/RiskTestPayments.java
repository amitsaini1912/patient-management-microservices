package org.pm.billingservice.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.pm.billingservice.card.CardInfo;
import org.pm.billingservice.fx.FxRate;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.payment.Payment;

/** Payments as the risk check sees them: card attached, client context attached, not yet authorized. */
final class RiskTestPayments {

    static final String FINGERPRINT = "fp_" + "ab".repeat(30);

    private RiskTestPayments() {}

    static Payment inr(long paise) {
        return withCard(Payment.create(UUID.randomUUID(), UUID.randomUUID(), Money.ofMinor(paise, "INR")));
    }

    /** Paid in USD, booked in INR at 83.00. */
    static Payment usd(long cents) {
        FxRate rate = new FxRate(UUID.randomUUID(), Currency.getInstance("USD"), Currency.getInstance("INR"),
                new BigDecimal("83.00"), "test", Instant.now());
        return withCard(Payment.create(UUID.randomUUID(), UUID.randomUUID(), Money.ofMinor(cents, "USD"), rate));
    }

    private static Payment withCard(Payment payment) {
        payment.attachCard(new CardInfo("tok_test_card", "4242", "VISA", FINGERPRINT, 12, 2099));
        payment.attachRiskContext(new RiskContext("203.0.113.9", "dev-1"));
        return payment;
    }
}
