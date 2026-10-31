package org.pm.billingservice.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 21: Money's rules for ANY amount, not just the examples in MoneyTest. jqwik generates the inputs (edge cases
 * like 0, 1, Long.MAX_VALUE included) and shrinks a failure to the smallest counterexample.
 */
class MoneyPropertiesTest {

    private static final long LIMIT = 1_000_000_000_000_000L;   // 10^18 minor units: far above any payment
    private static final Currency INR = Currency.getInstance("INR");

    @Provide
    Arbitrary<Currency> currencies() {
        return Arbitraries.of("INR", "USD", "EUR", "JPY", "KWD").map(Currency::getInstance);   // 2, 2, 2, 0, 3 decimals
    }

    @Property
    void additionIsCommutativeAndAssociative(@ForAll @LongRange(min = -LIMIT, max = LIMIT) long a,
                                             @ForAll @LongRange(min = -LIMIT, max = LIMIT) long b,
                                             @ForAll @LongRange(min = -LIMIT, max = LIMIT) long c) {
        Money x = new Money(a, INR), y = new Money(b, INR), z = new Money(c, INR);
        assertThat(x.plus(y)).isEqualTo(y.plus(x));
        assertThat(x.plus(y).plus(z)).isEqualTo(x.plus(y.plus(z)));
        assertThat(x.plus(y).minus(y)).isEqualTo(x);
        assertThat(x.minus(x).isZero()).isTrue();
        assertThat(x.negate().negate()).isEqualTo(x);
    }

    @Property
    void overflowIsAnErrorNeverAWrapAround(@ForAll @LongRange(min = 1, max = Long.MAX_VALUE) long a) {
        Money max = new Money(Long.MAX_VALUE, INR);
        assertThatThrownBy(() -> max.plus(new Money(a, INR))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> new Money(Long.MIN_VALUE, INR).minus(new Money(a, INR))).isInstanceOf(ArithmeticException.class);
    }

    @Property
    void allocationNeverLosesOrCreatesAMinorUnit(@ForAll @LongRange(min = 0, max = LIMIT / 1000) long amount,
                                                 @ForAll @Size(min = 1, max = 12) List<@IntRange(min = 0, max = 1000) Integer> ratios) {
        int[] r = ratios.stream().mapToInt(Integer::intValue).toArray();
        long total = ratios.stream().mapToLong(Integer::longValue).sum();
        if (total == 0) {
            assertThatThrownBy(() -> new Money(amount, INR).allocate(r)).isInstanceOf(IllegalArgumentException.class);
            return;
        }
        List<Money> parts = new Money(amount, INR).allocate(r);

        assertThat(parts.stream().mapToLong(Money::amountMinor).sum()).isEqualTo(amount);     // exact
        for (int i = 0; i < r.length; i++) {
            BigDecimal exactShare = BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(r[i]))
                    .divide(BigDecimal.valueOf(total), 10, RoundingMode.HALF_EVEN);
            long part = parts.get(i).amountMinor();
            assertThat(BigDecimal.valueOf(part).subtract(exactShare).abs()).isLessThan(BigDecimal.ONE);  // < 1 unit off
            if (r[i] == 0) {
                assertThat(part).isZero();                                                 // nothing for a 0 ratio
            }
        }
    }

    @Property
    void parseAndToDecimalRoundTrip(@ForAll("currencies") Currency currency, @ForAll @LongRange(min = 0, max = LIMIT) long minor) {
        Money money = new Money(minor, currency);
        assertThat(Money.parse(money.toDecimal().toPlainString(), currency.getCurrencyCode())).isEqualTo(money);
    }

    @Property
    void tooManyDecimalsIsRejectedNeverRounded(@ForAll("currencies") Currency currency,
                                               @ForAll @LongRange(min = 0, max = LIMIT) long minor,
                                               @ForAll @IntRange(min = 1, max = 9) int extraDigit) {
        String amount = new Money(minor, currency).toDecimal().toPlainString()
                + (currency.getDefaultFractionDigits() == 0 ? "." : "") + extraDigit;
        assertThatThrownBy(() -> Money.parse(amount, currency.getCurrencyCode())).isInstanceOf(IllegalArgumentException.class);
    }

    @Property
    void conversionIsRoundedOnceToTheNearestMinorUnit(@ForAll @LongRange(min = 0, max = LIMIT / 1000) long cents,
                                                      @ForAll @IntRange(min = 1, max = 99_999_999) int rateTimes1e6,
                                                      @ForAll("currencies") Currency target) {
        BigDecimal rate = BigDecimal.valueOf(rateTimes1e6, 6);
        Money usd = new Money(cents, Currency.getInstance("USD"));
        Money converted = usd.convertTo(target, rate);

        BigDecimal exactMinor = usd.toDecimal().multiply(rate).movePointRight(target.getDefaultFractionDigits());
        assertThat(converted.currency()).isEqualTo(target);
        assertThat(BigDecimal.valueOf(converted.amountMinor()).subtract(exactMinor).abs())
                .isLessThanOrEqualTo(new BigDecimal("0.5"));                                // at most half a unit
        Money oneCentMore = new Money(cents + 1, usd.currency()).convertTo(target, rate);
        assertThat(oneCentMore.amountMinor()).isGreaterThanOrEqualTo(converted.amountMinor());   // monotonic
    }
}
