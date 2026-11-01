package org.pm.billingservice.risk;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0028, the Day 23 herd: 60 payments ask at the same instant (the clients' retries the moment Postgres came back).
 * The fake engine behaves like the real one - one CPU, so every call in flight makes each answer slower (25 ms per
 * call queued) - and times out at the budget like the HTTP client does.
 */
class RiskHerdTest {

    private static final RiskVerdict ALLOW = new RiskVerdict(RiskDecision.ALLOW, RiskSource.ENGINE, List.of("R00_NONE"),
            0.05, "rf-1", "r-1", null);
    private static final int HERD = 60;

    private final AtomicInteger engineInFlight = new AtomicInteger();
    private final AtomicInteger engineMaxInFlight = new AtomicInteger();

    private final RiskEngineClient slowsDownWithLoad = (payment, timeout) -> {
        int queued = engineInFlight.incrementAndGet();
        engineMaxInFlight.accumulateAndGet(queued, Math::max);
        try {
            Duration answerIn = Duration.ofMillis(25L * queued);
            sleep(answerIn.compareTo(timeout) < 0 ? answerIn : timeout);
            if (answerIn.compareTo(timeout) >= 0) {
                throw new RiskEngineUnavailableException("timeout", "no answer in " + timeout.toMillis() + " ms", false, null);
            }
            return ALLOW;
        } finally {
            engineInFlight.decrementAndGet();
        }
    };

    @Test
    void withoutACapTheHerdTimesOutAndOpensTheBreaker() throws Exception {
        RiskAssessor assessor = assessor(1_000);

        List<RiskVerdict> verdicts = herd(assessor);

        assertThat(verdicts.stream().filter(v -> "timeout".equals(v.fallbackReason())).count()).isGreaterThan(HERD / 2);
        assertThat(assessor.circuitState()).isEqualTo(CircuitBreaker.State.OPEN); // -> 30 s of fallback after the herd
        assertThat(assessor.assess(RiskTestPayments.inr(100_00)).fallbackReason()).isEqualTo("circuit_open");
    }

    @Test
    void withTheCapTheExtraPaymentsAreDecidedAtOnceAndTheEngineStaysUsable() throws Exception {
        RiskAssessor assessor = assessor(8);

        List<RiskVerdict> verdicts = herd(assessor);

        assertThat(engineMaxInFlight.get()).isLessThanOrEqualTo(8);
        assertThat(verdicts).allSatisfy(v -> assertThat(v.fallbackReason()).isIn(null, "busy")); // no timeouts at all
        assertThat(verdicts.stream().filter(v -> v.source() == RiskSource.ENGINE).count()).isGreaterThanOrEqualTo(8);
        assertThat(assessor.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(assessor.assess(RiskTestPayments.inr(100_00))).isEqualTo(ALLOW); // the next payment: the engine
    }

    private List<RiskVerdict> herd(RiskAssessor assessor) throws Exception {
        ExecutorService clients = Executors.newFixedThreadPool(HERD);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<RiskVerdict>> futures = new ArrayList<>();
            for (int i = 0; i < HERD; i++) {
                futures.add(clients.submit(() -> {
                    go.await();
                    return assessor.assess(RiskTestPayments.inr(100_00));
                }));
            }
            go.countDown();
            List<RiskVerdict> verdicts = new ArrayList<>();
            for (Future<RiskVerdict> future : futures) {
                verdicts.add(future.get());
            }
            return verdicts;
        } finally {
            clients.shutdownNow();
        }
    }

    private RiskAssessor assessor(int maxConcurrent) {
        RiskProperties d = RiskAssessorTest.properties(true);
        RiskProperties p = new RiskProperties(true, d.baseUrl(), d.apiKey(), d.budget(), d.minAttempt(), d.merchantId(),
                d.fallback(), d.circuitBreaker(), maxConcurrent);
        return new RiskAssessor(p, new StaticListableBeanFactory(Map.of("engine", slowsDownWithLoad))
                .getBeanProvider(RiskEngineClient.class), new FallbackRiskRules(p), new SimpleMeterRegistry());
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
