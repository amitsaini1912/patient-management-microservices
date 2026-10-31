package org.pm.billingservice.risk;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackRiskRulesTest {

    private final FallbackRiskRules rules = new FallbackRiskRules(RiskAssessorTest.properties(true));

    @Test
    void normalPaymentsGoThrough() {
        RiskVerdict verdict = rules.decide(RiskTestPayments.inr(49_999_99), "timeout");
        assertThat(verdict.decision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(verdict.source()).isEqualTo(RiskSource.FALLBACK);
        assertThat(verdict.fallbackReason()).isEqualTo("timeout");
        assertThat(verdict.reasonCodes()).containsExactly("FB_DEFAULT");
        assertThat(verdict.score()).isNull();
    }

    @Test
    void bigPaymentsGoToReviewNeverToDecline() {
        assertThat(rules.decide(RiskTestPayments.inr(50_000_00), "x").decision()).isEqualTo(RiskDecision.HOLD);
        assertThat(rules.decide(RiskTestPayments.inr(9_999_999_00L), "x").decision()).isEqualTo(RiskDecision.HOLD);
    }

    @Test
    void foreignCardsAreComparedInRupeesWithALowerLimit() {
        // USD 120 at 83.00 = INR 9,960 -> allow; USD 121 = INR 10,043 -> hold
        assertThat(rules.decide(RiskTestPayments.usd(120_00), "x").decision()).isEqualTo(RiskDecision.ALLOW);
        RiskVerdict held = rules.decide(RiskTestPayments.usd(121_00), "x");
        assertThat(held.decision()).isEqualTo(RiskDecision.HOLD);
        assertThat(held.reasonCodes()).isEqualTo(List.of("FB_FOREIGN_HIGH_AMOUNT"));
        // USD 603 = INR 50,049: the general limit, in rupees
        assertThat(rules.decide(RiskTestPayments.usd(603_00), "x").reasonCodes()).containsExactly("FB_HIGH_AMOUNT");
    }
}
