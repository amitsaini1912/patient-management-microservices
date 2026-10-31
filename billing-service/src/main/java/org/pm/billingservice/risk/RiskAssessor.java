package org.pm.billingservice.risk;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.function.Supplier;
import org.pm.billingservice.payment.Payment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The risk decision for a payment, ALWAYS within the budget (300 ms) and never an exception (ADR 0017):
 *
 *   attempt -> circuit breaker -> HTTP call with what is left of the budget
 *   failed fast (connection refused, 502/503/504) and >= minAttempt left -> ONE retry
 *   anything else (timeout, 4xx, bad answer, circuit open, budget spent) -> FallbackRiskRules
 *
 * The breaker stops us paying the timeout on every payment while the engine is down: when it is open, payments go
 * straight to the fallback, and after `openFor` a few calls test whether the engine is back.
 */
@Component
@EnableConfigurationProperties(RiskProperties.class)
public class RiskAssessor {

    private static final Logger log = LoggerFactory.getLogger(RiskAssessor.class);

    private final RiskProperties properties;
    private final RiskEngineClient engine;   // null when billing.risk.enabled=false
    private final FallbackRiskRules fallback;
    private final MeterRegistry meters;
    private final CircuitBreaker breaker;
    private final Retry retry;

    public RiskAssessor(RiskProperties properties, ObjectProvider<RiskEngineClient> engine, FallbackRiskRules fallback,
                        MeterRegistry meters) {
        this.properties = properties;
        this.engine = properties.enabled() ? engine.getIfAvailable() : null;
        this.fallback = fallback;
        this.meters = meters;
        RiskProperties.CircuitBreaker cb = properties.circuitBreaker();
        this.breaker = CircuitBreaker.of("risk-engine", CircuitBreakerConfig.custom()
                .slidingWindowSize(cb.window())
                .minimumNumberOfCalls(cb.minimumCalls())
                .failureRateThreshold(cb.failureRate())
                .waitDurationInOpenState(cb.openFor())
                .permittedNumberOfCallsInHalfOpenState(3)
                .build());
        this.retry = Retry.of("risk-engine", RetryConfig.custom()
                .maxAttempts(2)                                   // the first call + one retry
                .waitDuration(Duration.ofMillis(10))
                .retryOnException(e -> e instanceof RiskEngineUnavailableException r && r.retryable())
                .failAfterMaxAttempts(false)
                .build());
        this.breaker.getEventPublisher().onStateTransition(event ->
                log.warn("risk engine circuit breaker: {}", event.getStateTransition()));
    }

    public RiskVerdict assess(Payment payment) {
        long started = System.nanoTime();
        RiskVerdict verdict = decide(payment, started + properties.budget().toNanos());
        meters.counter("billing.risk.decisions", "decision", verdict.decision().name(), "source", verdict.source().name(),
                "fallback_reason", verdict.fallbackReason() == null ? "none" : verdict.fallbackReason()).increment();
        meters.timer("billing.risk.duration", "source", verdict.source().name())
                .record(Duration.ofNanos(System.nanoTime() - started));
        return verdict;
    }

    /** Open / closed / half-open: for tests and diagnostics. */
    public CircuitBreaker.State circuitState() {
        return breaker.getState();
    }

    private RiskVerdict decide(Payment payment, long deadlineNanos) {
        if (engine == null) {
            return fallback.decide(payment, "disabled");
        }
        Supplier<RiskVerdict> attempt = () -> {
            Duration left = Duration.ofNanos(deadlineNanos - System.nanoTime());
            if (left.compareTo(properties.minAttempt()) < 0) {
                throw new RiskEngineUnavailableException("budget_spent", "no time left for a risk engine call", false, null);
            }
            return engine.assess(payment, left);
        };
        try {
            return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(breaker, attempt)).get();
        } catch (CallNotPermittedException e) {
            return fallback.decide(payment, "circuit_open");
        } catch (RiskEngineUnavailableException e) {
            log.warn("risk engine not used for payment {} ({}): {}", payment.getId(), e.reason(), e.getMessage());
            return fallback.decide(payment, e.reason());
        } catch (RuntimeException e) {   // a bug on our side must not stop the payment either
            log.error("risk check failed for payment {}, using fallback rules", payment.getId(), e);
            return fallback.decide(payment, "error");
        }
    }
}
