package org.pm.billingservice.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * An amount of money in ONE currency, stored as a whole number of minor units
 * (paise for INR, cents for USD/EUR). ₹12.34 is stored as 1234.
 *
 * Why a long and not double: 0.1 + 0.2 != 0.3 in floating point, so a double
 * slowly loses paise. A long is exact. There is deliberately no constructor
 * or factory that accepts a double.
 *
 * Immutable: every operation returns a new Money.
 */
public record Money(long amountMinor, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency is required");
        if (currency.getDefaultFractionDigits() < 0) {
            // e.g. XAU (gold) has no minor unit - not a payment currency
            throw new IllegalArgumentException("unsupported currency: " + currency);
        }
    }

    public static Money ofMinor(long amountMinor, String currencyCode) {
        return new Money(amountMinor, Currency.getInstance(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(0, currency);
    }

    /**
     * Parse a decimal STRING like "12.34" (from JSON or a CSV file).
     * More decimal places than the currency allows is an error, never a silent round:
     * "12.345" INR is rejected because a paise cannot be split.
     */
    public static Money parse(String amount, String currencyCode) {
        Currency currency = Currency.getInstance(currencyCode);
        BigDecimal decimal = new BigDecimal(amount);
        try {
            BigDecimal minor = decimal.setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY)
                    .movePointRight(currency.getDefaultFractionDigits());
            return new Money(minor.longValueExact(), currency);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "amount " + amount + " has too many decimals for " + currencyCode, e);
        }
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        // addExact throws on overflow instead of silently wrapping to a negative number
        return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(amountMinor, other.amountMinor), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(amountMinor), currency);
    }

    public boolean isZero() {
        return amountMinor == 0;
    }

    public boolean isPositive() {
        return amountMinor > 0;
    }

    public boolean isNegative() {
        return amountMinor < 0;
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amountMinor > other.amountMinor;
    }

    /**
     * Split this amount by ratios without losing or creating a single minor unit.
     * ₹1.00 split 1:1:1 gives [0.34, 0.33, 0.33]: every part gets its share rounded down, then the leftover paise
     * go to the parts with the LARGEST remainders (ties: the earlier part), so the parts add back to exactly the
     * original and every part is less than one paisa from its exact share.
     * (Day 21: until then the leftovers went to the first parts - a property test found ₹0.08 split 5:1:34 giving
     * [0.02, 0.00, 0.06] where the exact shares are 0.01, 0.002, 0.068.)
     */
    public List<Money> allocate(int... ratios) {
        if (ratios.length == 0) {
            throw new IllegalArgumentException("at least one ratio is required");
        }
        if (amountMinor < 0) {
            throw new IllegalArgumentException("cannot allocate a negative amount");
        }
        long total = 0;
        for (int ratio : ratios) {
            if (ratio < 0) {
                throw new IllegalArgumentException("ratios must not be negative");
            }
            total += ratio;
        }
        if (total == 0) {
            throw new IllegalArgumentException("ratios must not all be zero");
        }

        long[] parts = new long[ratios.length];
        long[] fractions = new long[ratios.length];   // remainder of amount * ratio / total, in 1/total units
        long remainder = amountMinor;
        for (int i = 0; i < ratios.length; i++) {
            // multiplyExact: amount * ratio could overflow for very large amounts
            long scaled = Math.multiplyExact(amountMinor, ratios[i]);
            parts[i] = scaled / total;
            fractions[i] = scaled % total;
            remainder -= parts[i];
        }
        // the leftover minor units (fewer than the number of parts) go to the largest fractions, earlier part first
        Integer[] order = new Integer[ratios.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (x, y) -> fractions[x] != fractions[y] ? Long.compare(fractions[y], fractions[x])
                : Integer.compare(x, y));
        for (int k = 0; remainder > 0; k++, remainder--) {
            parts[order[k]]++;
        }

        List<Money> result = new ArrayList<>(ratios.length);
        for (long part : parts) {
            result.add(new Money(part, currency));
        }
        return result;
    }

    /**
     * Convert to another currency at an exchange rate (1 unit of this currency = rate units of target).
     * The only place BigDecimal math happens. Rounded ONCE, at the end, with HALF_EVEN
     * ("banker's rounding"): exact halves go to the even neighbour, so over many
     * conversions rounding errors cancel out instead of always going up.
     */
    public Money convertTo(Currency target, BigDecimal rate) {
        Objects.requireNonNull(target, "target currency is required");
        if (rate == null || rate.signum() <= 0) {
            throw new IllegalArgumentException("rate must be positive");
        }
        BigDecimal converted = toDecimal().multiply(rate)
                .setScale(target.getDefaultFractionDigits(), RoundingMode.HALF_EVEN);
        return new Money(converted.movePointRight(target.getDefaultFractionDigits()).longValueExact(), target);
    }

    /** Major units for display / reports only, e.g. 1234 INR -> 12.34. Never used for math. */
    public BigDecimal toDecimal() {
        return BigDecimal.valueOf(amountMinor, currency.getDefaultFractionDigits());
    }

    @Override
    public String toString() {
        return currency.getCurrencyCode() + " " + toDecimal().toPlainString();
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other amount is required");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }
}
