package org.pm.billingservice.fx;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.pm.billingservice.money.Money;

/**
 * 1 {@code base} = {@code rate} {@code quote}, valid from {@code asOf}. {@code id} is null for the implicit
 * same-currency rate 1.
 */
public record FxRate(UUID id, Currency base, Currency quote, BigDecimal rate, String source, Instant asOf) {

    public static FxRate identity(Currency currency) {
        return new FxRate(null, currency, currency, BigDecimal.ONE, "identity", Instant.EPOCH);
    }

    /** base -> quote, rounded once, HALF_EVEN, to the quote currency's minor unit (see docs/ROUNDING.md). */
    public Money convert(Money amount) {
        if (!amount.currency().equals(base)) {
            throw new IllegalArgumentException("rate is for " + base + ", amount is in " + amount.currency());
        }
        return base.equals(quote) ? amount : amount.convertTo(quote, rate);
    }
}
