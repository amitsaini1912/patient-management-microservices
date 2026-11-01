package org.pm.billingservice.risk;

import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Warms the risk engine client BEFORE billing reports ready (Day 23 finding, ADR 0026).
 *
 * After every restart in the chaos runs, the first risk checks missed the 300 ms budget (new connections, classes
 * loaded and code run for the first time): 7 timeouts -> breaker open -> ~30 s of fallback decisions for real payments.
 * Spring Boot runs ApplicationRunners before it moves readiness to ACCEPTING_TRAFFIC, so while this runs
 * /actuator/health/readiness (and /actuator/health) answer 503 OUT_OF_SERVICE and k8s / start-stack.sh send nothing.
 *
 * Bounded: at most {@code calls} x {@code timeout}, and it gives up at the first unreachable call (engine down: start
 * anyway, the fallback covers it). Never fails startup.
 */
@Component
@ConditionalOnProperty(name = "billing.risk.enabled", havingValue = "true")
public class RiskEngineWarmup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RiskEngineWarmup.class);

    private final HttpRiskEngineClient client;
    private final int calls;
    private final Duration timeout;

    public RiskEngineWarmup(HttpRiskEngineClient client,
                            @Value("${billing.risk.warm-up.calls:10}") int calls,
                            @Value("${billing.risk.warm-up.timeout:1s}") Duration timeout) {
        this.client = client;
        this.calls = calls;
        this.timeout = timeout;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (calls <= 0) {
            return;
        }
        try {
            List<Long> took = client.warmUp(calls, timeout);
            if (took.isEmpty()) {
                log.warn("risk engine warm-up: engine unreachable, starting anyway (payments use the fallback until it is back)");
            } else {
                log.info("risk engine warm-up: {} call(s), first {} ms, last {} ms", took.size(), took.get(0),
                        took.get(took.size() - 1));
            }
        } catch (RuntimeException e) {
            log.warn("risk engine warm-up failed, starting anyway: {}", e.toString());
        }
    }
}
