package org.pm.billingservice.money;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Plain unit test - no Spring, no DB. Runs in milliseconds.
class MoneyTest {

    private static final Currency INR = Currency.getInstance("INR");
    private static final Currency USD = Currency.getInstance("USD");

    @Test
    void storesAmountAsMinorUnits() {
        Money money = Money.parse("12.34", "INR");

        assertThat(money.amountMinor()).isEqualTo(1234);
        assertThat(money.toString()).isEqualTo("INR 12.34");
    }

    @Test
    void floatingPointProblemDoesNotExistInMinorUnits() {
        // with double: 0.1 + 0.2 = 0.30000000000000004
        Money sum = Money.parse("0.10", "USD").plus(Money.parse("0.20", "USD"));

        assertThat(sum).isEqualTo(Money.parse("0.30", "USD"));
    }

    @Test
    void rejectsMoreDecimalsThanTheCurrencyHas() {
        assertThatThrownBy(() -> Money.parse("12.345", "INR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too many decimals");
    }

    @Test
    void acceptsTrailingZerosBeyondMinorUnits() {
        assertThat(Money.parse("12.3400", "INR").amountMinor()).isEqualTo(1234);
    }

    @Test
    void rejectsCurrencyWithoutMinorUnit() {
        assertThatThrownBy(() -> Money.ofMinor(100, "XAU")) // gold
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullCurrency() {
        assertThatThrownBy(() -> new Money(100, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void plusAndMinusInSameCurrency() {
        Money a = Money.ofMinor(1000, "INR");
        Money b = Money.ofMinor(250, "INR");

        assertThat(a.plus(b).amountMinor()).isEqualTo(1250);
        assertThat(a.minus(b).amountMinor()).isEqualTo(750);
        assertThat(b.minus(a).isNegative()).isTrue();
        assertThat(a.negate().amountMinor()).isEqualTo(-1000);
    }

    @Test
    void addingDifferentCurrenciesFails() {
        assertThatThrownBy(() -> Money.ofMinor(100, "INR").plus(Money.ofMinor(100, "USD")))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void overflowFailsInsteadOfWrappingToNegative() {
        Money max = new Money(Long.MAX_VALUE, INR);

        assertThatThrownBy(() -> max.plus(Money.ofMinor(1, "INR")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void equalityUsesAmountAndCurrency() {
        assertThat(Money.ofMinor(500, "INR")).isEqualTo(new Money(500, INR));
        assertThat(Money.ofMinor(500, "INR")).isNotEqualTo(Money.ofMinor(500, "USD"));
    }

    @Test
    void allocateNeverLosesAPaisa() {
        List<Money> parts = Money.ofMinor(100, "INR").allocate(1, 1, 1);

        assertThat(parts).extracting(Money::amountMinor).containsExactly(34L, 33L, 33L);
    }

    @Test
    void allocateByUnevenRatios() {
        // e.g. insurer pays 70%, patient pays 30% of a ₹1,000.05 bill
        List<Money> parts = Money.parse("1000.05", "INR").allocate(70, 30);

        assertThat(parts.get(0).plus(parts.get(1))).isEqualTo(Money.parse("1000.05", "INR"));
        assertThat(parts).extracting(Money::amountMinor).containsExactly(70004L, 30001L);
    }

    @Test
    void allocateSkipsZeroRatios() {
        List<Money> parts = Money.ofMinor(5, "INR").allocate(0, 1, 1);

        assertThat(parts).extracting(Money::amountMinor).containsExactly(0L, 3L, 2L);
    }

    @Test
    void allocateRejectsBadInput() {
        assertThatThrownBy(() -> Money.ofMinor(100, "INR").allocate())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.ofMinor(100, "INR").allocate(0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.ofMinor(-100, "INR").allocate(1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void convertUsesRateAndTargetMinorUnits() {
        Money usd = Money.parse("100.00", "USD");

        Money inr = usd.convertTo(INR, new BigDecimal("83.1234"));

        assertThat(inr).isEqualTo(Money.parse("8312.34", "INR"));
    }

    // HALF_EVEN = banker's rounding: an exact half goes to the EVEN neighbour.
    @ParameterizedTest
    @CsvSource({
            "0.125, 0.12",  // half, 2 is even -> down
            "0.135, 0.14",  // half, 4 is even -> up
            "0.126, 0.13",  // more than half -> up (normal rounding)
            "0.124, 0.12",  // less than half -> down
    })
    void convertRoundsHalfEven(String rate, String expected) {
        Money oneDollar = Money.parse("1.00", "USD");

        Money result = oneDollar.convertTo(Currency.getInstance("EUR"), new BigDecimal(rate));

        assertThat(result).isEqualTo(Money.parse(expected, "EUR"));
    }

    @Test
    void convertRejectsZeroOrNegativeRate() {
        Money usd = Money.ofMinor(100, "USD");

        assertThatThrownBy(() -> usd.convertTo(INR, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> usd.convertTo(INR, new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void comparisons() {
        assertThat(Money.ofMinor(2, "USD").isGreaterThan(Money.ofMinor(1, "USD"))).isTrue();
        assertThat(Money.zero(USD).isZero()).isTrue();
        assertThat(Money.ofMinor(1, "USD").isPositive()).isTrue();
    }
}
