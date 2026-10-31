package org.pm.billingservice.risk;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.payment.Payment;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** Budget, one retry, circuit breaker, fallback - with a scripted engine (no HTTP). */
class RiskAssessorTest {

    static RiskProperties properties(boolean enabled) {
        return new RiskProperties(enabled, URI.create("http://localhost:1"), "billing-test-key-0001",
                Duration.ofMillis(300), Duration.ofMillis(50), "hospital-main",
                new RiskProperties.Fallback(50_000_00, 10_000_00),
                new RiskProperties.CircuitBreaker(20, 10, 50f, Duration.ofSeconds(30)));
    }

    private static final RiskVerdict HOLD = new RiskVerdict(RiskDecision.HOLD, RiskSource.ENGINE, List.of("R01_VELOCITY"),
            0.56, "rf-1", "r-1", null);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<Duration> timeouts = new CopyOnWriteArrayList<>();

    interface Script {
        RiskVerdict call(int attempt, Duration timeout) throws Exception;
    }

    private RiskAssessor assessor(boolean enabled, Script script) {
        AtomicInteger attempts = new AtomicInteger();
        RiskEngineClient engine = (payment, timeout) -> {
            timeouts.add(timeout);
            try {
                return script.call(attempts.incrementAndGet(), timeout);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        RiskProperties p = properties(enabled);
        return new RiskAssessor(p, new StaticListableBeanFactory(Map.of("engine", engine)).getBeanProvider(RiskEngineClient.class),
                new FallbackRiskRules(p), meters);
    }

    private static RiskEngineUnavailableException down(String reason, boolean retryable) {
        return new RiskEngineUnavailableException(reason, reason, retryable, null);
    }

    @Test
    void disabledMeansBillingRulesOnlyAndNoCall() {
        RiskVerdict verdict = assessor(false, (a, t) -> HOLD).assess(RiskTestPayments.inr(100_00));
        assertThat(verdict.source()).isEqualTo(RiskSource.FALLBACK);
        assertThat(verdict.fallbackReason()).isEqualTo("disabled");
        assertThat(timeouts).isEmpty();
    }

    @Test
    void theEnginesAnswerIsUsedAndCounted() {
        assertThat(assessor(true, (a, t) -> HOLD).assess(RiskTestPayments.inr(100_00))).isEqualTo(HOLD);
        assertThat(timeouts.get(0)).isLessThanOrEqualTo(Duration.ofMillis(300)).isGreaterThan(Duration.ofMillis(250));
        assertThat(meters.counter("billing.risk.decisions", "decision", "HOLD", "source", "ENGINE",
                "fallback_reason", "none").count()).isEqualTo(1);
    }

    @Test
    void oneRetryAfterAFastFailureWithWhatIsLeftOfTheBudget() {
        RiskVerdict verdict = assessor(true, (a, t) -> {
            if (a == 1) {
                Thread.sleep(100);
                throw down("http_503", true);
            }
            return HOLD;
        }).assess(RiskTestPayments.inr(100_00));
        assertThat(verdict).isEqualTo(HOLD);
        assertThat(timeouts).hasSize(2);
        assertThat(timeouts.get(1)).isLessThan(Duration.ofMillis(200));   // the retry only gets the rest
    }

    @Test
    void neverMoreThanOneRetry() {
        RiskVerdict verdict = assessor(true, (a, t) -> { throw down("unreachable", true); }).assess(RiskTestPayments.inr(100_00));
        assertThat(verdict.fallbackReason()).isEqualTo("unreachable");
        assertThat(timeouts).hasSize(2);
    }

    @Test
    void noRetryWhenItCantHelp() {
        RiskVerdict verdict = assessor(true, (a, t) -> { throw down("http_422", false); }).assess(RiskTestPayments.inr(60_000_00));
        assertThat(timeouts).hasSize(1);
        assertThat(verdict.source()).isEqualTo(RiskSource.FALLBACK);
        assertThat(verdict.decision()).isEqualTo(RiskDecision.HOLD);                 // the fallback's big-amount rule
        assertThat(verdict.fallbackReason()).isEqualTo("http_422");
    }

    @Test
    void noRetryWithoutTimeForIt() {
        long started = System.nanoTime();
        RiskVerdict verdict = assessor(true, (a, t) -> {
            Thread.sleep(270);                                                        // 30 ms left < minAttempt 50 ms
            throw down("http_503", true);
        }).assess(RiskTestPayments.inr(100_00));
        assertThat(timeouts).hasSize(1);
        assertThat(verdict.fallbackReason()).isEqualTo("budget_spent");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(400));
    }

    @Test
    void anOpenCircuitSkipsTheEngineEntirely() {
        RiskAssessor assessor = assessor(true, (a, t) -> { throw down("timeout", false); });
        for (int i = 0; i < 10; i++) {
            assessor.assess(RiskTestPayments.inr(100_00));
        }
        assertThat(assessor.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
        RiskVerdict verdict = assessor.assess(RiskTestPayments.inr(100_00));
        assertThat(verdict.fallbackReason()).isEqualTo("circuit_open");
        assertThat(timeouts).hasSize(10);                                            // the 11th never reached it
        assertThat(meters.counter("billing.risk.decisions", "decision", "ALLOW", "source", "FALLBACK",
                "fallback_reason", "circuit_open").count()).isEqualTo(1);
    }

    @Test
    void aBugOnOurSideStillGivesADecision() {
        Payment payment = RiskTestPayments.inr(100_00);
        RiskVerdict verdict = assessor(true, (a, t) -> { throw new NullPointerException("oops"); }).assess(payment);
        assertThat(verdict.decision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(verdict.fallbackReason()).isEqualTo("error");
    }
}
