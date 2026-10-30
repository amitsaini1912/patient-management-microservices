package org.pm.cardvault.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PanMaskerTest {

    @Test
    void masksLuhnValidCardNumbersInAnyCommonFormat() {
        assertThat(PanMasker.mask("card 4111111111111111 declined")).isEqualTo("card [PAN ****1111] declined");
        assertThat(PanMasker.mask("x=4111 1111 1111 1111;")).isEqualTo("x=[PAN ****1111];");
        assertThat(PanMasker.mask("4111-1111-1111-1111")).isEqualTo("[PAN ****1111]");
        assertThat(PanMasker.mask("amex 378282246310005 and visa 4242424242424242"))
                .isEqualTo("amex [PAN ****0005] and visa [PAN ****4242]");
        assertThat(PanMasker.mask("{\"pan\":\"5555555555554444\"}")).isEqualTo("{\"pan\":\"[PAN ****4444]\"}");
    }

    @Test
    void leavesOtherNumbersAlone() {
        String order = "order 1234567812345678 at 1730280000123"; // 16 and 13 digits, both fail Luhn
        assertThat(PanMasker.mask(order)).isEqualTo(order);
        assertThat(PanMasker.mask("tok_9f3a1c 42 2026-10-30")).isEqualTo("tok_9f3a1c 42 2026-10-30");
        assertThat(PanMasker.mask("phone 9876543210")).isEqualTo("phone 9876543210"); // too short
    }
}
