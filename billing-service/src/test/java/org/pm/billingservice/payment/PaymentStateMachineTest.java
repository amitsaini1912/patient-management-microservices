package org.pm.billingservice.payment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * All 9 x 9 = 81 (from, to) pairs. The expected list is typed out here on purpose instead of
 * read from PaymentStateMachine: if someone adds a move to the map by mistake, this test fails.
 */
class PaymentStateMachineTest {

    private static final Set<String> EXPECTED_ALLOWED = Set.of(
            "CREATED -> AUTHORIZED",
            "CREATED -> PENDING_REVIEW",
            "CREATED -> FAILED",
            "PENDING_REVIEW -> AUTHORIZED",
            "PENDING_REVIEW -> FAILED",
            "AUTHORIZED -> CAPTURED",
            "AUTHORIZED -> VOIDED",
            "AUTHORIZED -> EXPIRED",
            "CAPTURED -> PARTIALLY_REFUNDED",
            "CAPTURED -> REFUNDED",
            "PARTIALLY_REFUNDED -> PARTIALLY_REFUNDED",
            "PARTIALLY_REFUNDED -> REFUNDED");

    static Stream<Arguments> allowedMoves() {
        return allPairs().filter(a -> EXPECTED_ALLOWED.contains(label(a)));
    }

    static Stream<Arguments> disallowedMoves() {
        return allPairs().filter(a -> !EXPECTED_ALLOWED.contains(label(a)));
    }

    @ParameterizedTest(name = "{0} -> {1} is allowed")
    @MethodSource("allowedMoves")
    void allowedMove(PaymentStatus from, PaymentStatus to) {
        assertThat(PaymentStateMachine.canMove(from, to)).isTrue();
        PaymentStateMachine.requireMove(UUID.randomUUID(), from, to); // does not throw
    }

    @ParameterizedTest(name = "{0} -> {1} is refused")
    @MethodSource("disallowedMoves")
    void disallowedMove(PaymentStatus from, PaymentStatus to) {
        assertThat(PaymentStateMachine.canMove(from, to)).isFalse();
        assertThatThrownBy(() -> PaymentStateMachine.requireMove(UUID.randomUUID(), from, to))
                .isInstanceOf(IllegalPaymentStateException.class)
                .hasMessageContaining("cannot move from " + from + " to " + to);
    }

    @Test
    void everyPairIsCovered() {
        assertThat(allowedMoves().count() + disallowedMoves().count()).isEqualTo(81);
        assertThat(allowedMoves().count()).isEqualTo(EXPECTED_ALLOWED.size()); // no typo in the expected list
    }

    @Test
    void terminalStatuses() {
        assertThat(Stream.of(PaymentStatus.values()).filter(PaymentStatus::isTerminal))
                .containsExactlyInAnyOrder(
                        PaymentStatus.REFUNDED, PaymentStatus.FAILED, PaymentStatus.VOIDED, PaymentStatus.EXPIRED);
    }

    private static Stream<Arguments> allPairs() {
        List<Arguments> pairs = new ArrayList<>();
        for (PaymentStatus from : PaymentStatus.values()) {
            for (PaymentStatus to : PaymentStatus.values()) {
                pairs.add(Arguments.of(from, to));
            }
        }
        return pairs.stream();
    }

    private static String label(Arguments pair) {
        return pair.get()[0] + " -> " + pair.get()[1];
    }
}
