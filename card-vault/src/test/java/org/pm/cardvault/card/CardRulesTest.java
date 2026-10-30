package org.pm.cardvault.card;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Luhn, normalization, brands. Test PANs are the networks' published test numbers. */
class CardRulesTest {

    @ParameterizedTest
    @ValueSource(strings = {"4111111111111111", "4242424242424242", "5555555555554444", "378282246310005",
            "6011111111111117", "2223003122003222", "4000056655665556", "79927398713"})
    void luhnAcceptsValidNumbers(String pan) {
        assertThat(Luhn.isValid(pan)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"4111111111111112", "4242424242424241", "1234567812345678", "79927398710", "", "41111a1111111111"})
    void luhnRejectsTyposAndJunk(String pan) {
        assertThat(Luhn.isValid(pan)).isFalse();
    }

    @Test
    void luhnCatchesEverySingleDigitTypo() {
        String pan = "4111111111111111";
        for (int i = 0; i < pan.length(); i++) {
            for (char d = '0'; d <= '9'; d++) {
                if (d != pan.charAt(i)) {
                    String typo = pan.substring(0, i) + d + pan.substring(i + 1);
                    assertThat(Luhn.isValid(typo)).as("typo at %d", i).isFalse();
                }
            }
        }
    }

    @Test
    void normalizeAllowsSpacesAndDashesAndNeverEchoesTheNumber() {
        assertThat(CardService.normalize("4111 1111-1111 1111")).isEqualTo("4111111111111111");
        for (String bad : new String[] {"4111111111111112", "411111111111", "41111111111111111111", "4111x11111111111"}) {
            assertThatThrownBy(() -> CardService.normalize(bad))
                    .isInstanceOf(InvalidCardException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContainPattern("\\d{4}"));
        }
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "4111111111111111, VISA",
            "5555555555554444, MASTERCARD",
            "2223003122003222, MASTERCARD",
            "378282246310005, AMEX",
            "6011111111111117, DISCOVER",
            "6521000000000009, RUPAY",
            "6070000000000003, RUPAY",
            "9999999999999995, UNKNOWN"
    })
    void brandFromBin(String pan, CardBrand brand) {
        assertThat(CardBrand.of(pan)).isEqualTo(brand);
    }
}
