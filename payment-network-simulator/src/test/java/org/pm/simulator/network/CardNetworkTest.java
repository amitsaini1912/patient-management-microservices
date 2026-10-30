package org.pm.simulator.network;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.pm.simulator.issuer.Issuer;
import org.pm.simulator.issuer.IssuerProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Field 6: transaction amount -> cardholder billing amount, HALF_EVEN to the paisa. */
class CardNetworkTest {

    private final CardNetwork network = new CardNetwork(
            new Issuer(new IssuerProperties("INR", 0, Duration.ZERO)),
            new NetworkProperties(Duration.ofSeconds(1), Map.of("USD", new BigDecimal("83.50"))),
            new NetworkLog(), new TraceNumbers());

    @Test
    void sameCurrencyIsUnchanged() {
        assertThat(network.toBilling(12_345, "INR")).isEqualTo(12_345);
    }

    @Test
    void foreignAmountIsConvertedWithBankersRounding() {
        assertThat(network.toBilling(100_00, "USD")).isEqualTo(8_350_00);
        assertThat(network.toBilling(1, "USD")).isEqualTo(84);   // 0.835 -> 0.84 (3 is odd: round to even 4)
        assertThat(network.toBilling(3, "USD")).isEqualTo(250);  // 2.505 -> 2.50 (0 is even: stays)
    }

    @Test
    void unknownCurrencyIsRefused() {
        assertThat(network.supports("USD")).isTrue();
        assertThat(network.supports("GBP")).isFalse();
        assertThatThrownBy(() -> network.toBilling(100, "GBP")).isInstanceOf(IllegalArgumentException.class);
    }
}
