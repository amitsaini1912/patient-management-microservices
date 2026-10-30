package org.pm.billingservice.fx;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.pm.billingservice.money.Money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Conversion examples from docs/ROUNDING.md. */
class FxRateTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency INR = Currency.getInstance("INR");
    private static final Currency JPY = Currency.getInstance("JPY");

    private static FxRate rate(Currency base, Currency quote, String rate) {
        return new FxRate(null, base, quote, new BigDecimal(rate), "test", Instant.EPOCH);
    }

    @ParameterizedTest(name = "USD {0} x {1} = INR {2}")
    @CsvSource({
            "100.00, 83.25,      8325.00",
            "100.00, 83.2550,    8325.50",
            "0.01,   83.25,      0.83",     // 0.8325 -> 0.83
            "0.01,   83.50,      0.84",     // 0.835  -> 0.84 (half: 3 is odd -> up to even 4)
            "0.01,   82.50,      0.82",     // 0.825  -> 0.82 (half: 2 is even -> stays)
            "12.34,  83.12345678, 1025.74"  // 1025.7434566652 -> 1025.74
    })
    void usdToInrRoundsOnceHalfEvenToPaise(String usd, String fx, String expectedInr) {
        Money result = rate(USD, INR, fx).convert(Money.parse(usd, "USD"));

        assertThat(result).isEqualTo(Money.parse(expectedInr, "INR"));
    }

    @ParameterizedTest(name = "USD {0} x {1} = JPY {2}")
    @CsvSource({
            "1.00, 151.50, 152",   // 151.5 -> 152 (half, 1 is odd -> even 2)
            "1.00, 152.50, 152",   // 152.5 -> 152 (half, 2 is even -> stays)
            "1.00, 151.49, 151"
    })
    void yenHasNoMinorUnitSoItRoundsToWholeYen(String usd, String fx, String expectedYen) {
        // JPY isn't a supported payment currency; it's here because exponent 0 is where rounding bugs show
        Money result = rate(USD, JPY, fx).convert(Money.parse(usd, "USD"));

        assertThat(result.amountMinor()).isEqualTo(Long.parseLong(expectedYen));
        assertThat(JPY.getDefaultFractionDigits()).isZero();
    }

    @Test
    void sameCurrencyIsIdentityAndWrongBaseIsRefused() {
        Money inr = Money.parse("10.00", "INR");
        assertThat(FxRate.identity(INR).convert(inr)).isEqualTo(inr);
        assertThatThrownBy(() -> rate(USD, INR, "83").convert(inr)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void convertingEachPartThenAddingCanDifferFromConvertingTheTotal() {
        // why refunds are converted per refund and the difference is booked, never "fixed" by fudging a line
        FxRate fx = rate(USD, INR, "83.335");
        Money whole = fx.convert(Money.parse("0.02", "USD"));                       // 1.6667 -> 1.67
        Money parts = fx.convert(Money.parse("0.01", "USD")).plus(fx.convert(Money.parse("0.01", "USD"))); // 0.83+0.83
        assertThat(whole).isEqualTo(Money.parse("1.67", "INR"));
        assertThat(parts).isEqualTo(Money.parse("1.66", "INR"));
    }
}
