package org.pm.billingservice.ratelimit;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Per-card limit on authorization attempts (ADR 0015): a token bucket per card in Redis, checked and taken in one
 * Lua script (ratelimit/card_token_bucket.lua). Stops "card testing" - a fraudster trying one stolen card again and
 * again - and protects the issuer relationship (schemes fine merchants for excessive retries).
 *
 * The key is the card's FINGERPRINT, not its token: the vault hands out a new token for every tokenization
 * (ADR 0013), so a per-token limit could be dodged by simply tokenizing the same card again.
 *
 * Fails OPEN: if Redis is down or slow, the attempt is allowed and a warning logged. A broken limiter must not stop
 * every payment; the gateway's per-user limit and the issuer's own checks still apply.
 *
 * Breaker (ADR 0025): failing open still cost each authorize Redis's 200 ms timeout (Day 23 chaos: checkout p95
 * 0.13 s -> 1.05 s with the gateway's limiter). After >= 50% failures of the last 10 calls (at least 5) Redis is skipped
 * for 10 s - attempts allowed at once, one warning per state change instead of one per call - then 2 trial calls.
 * Gauge billing_ratelimit_card_circuit_state: 0 closed, 1 open, 2 half-open.
 */
@Component
@EnableConfigurationProperties(CardRateLimitProperties.class)
public class CardRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(CardRateLimiter.class);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT =
            RedisScript.of(new ClassPathResource("ratelimit/card_token_bucket.lua"), List.class);
    private static final String KEY_PREFIX = "billing:ratelimit:card:";

    /** @param retryAfter how long until the next attempt is allowed; zero when allowed */
    public record Decision(boolean allowed, long remaining, Duration retryAfter) {}

    private final StringRedisTemplate redis;
    private final CardRateLimitProperties properties;
    private final CircuitBreaker breaker;

    @Autowired
    public CardRateLimiter(StringRedisTemplate redis, CardRateLimitProperties properties, MeterRegistry meters) {
        this(redis, properties, Duration.ofSeconds(10));
        Gauge.builder("billing.ratelimit.card.circuit.state", breaker, b -> switch (b.getState()) {
                    case CLOSED -> 0;
                    case OPEN -> 1;
                    case HALF_OPEN -> 2;
                    default -> 3;
                }).description("Card rate limiter Redis breaker: 0 closed, 1 open (limit not enforced), 2 half-open")
                .register(meters);
    }

    CardRateLimiter(StringRedisTemplate redis, CardRateLimitProperties properties, Duration openFor) {
        this.redis = redis;
        this.properties = properties;
        this.breaker = CircuitBreaker.of("card-rate-limiter-redis", CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(openFor)
                .permittedNumberOfCallsInHalfOpenState(2)
                .build());
        breaker.getEventPublisher().onStateTransition(event -> log.warn(
                "card rate limiter Redis breaker {} (open = limit not enforced, attempts allowed without asking Redis)",
                event.getStateTransition()));
    }

    CircuitBreaker breaker() {
        return breaker;
    }

    /** Takes one attempt for this card, or throws {@link CardRateLimitedException}. */
    public void acquire(String cardFingerprint) {
        if (!properties.enabled()) {
            return;
        }
        Decision decision = tryAcquire(cardFingerprint);
        if (!decision.allowed()) {
            log.info("card {}... rate-limited, retry in {}", cardFingerprint.substring(0, Math.min(8, cardFingerprint.length())),
                    decision.retryAfter());
            throw new CardRateLimitedException(decision.retryAfter());
        }
    }

    Decision tryAcquire(String cardFingerprint) {
        if (!breaker.tryAcquirePermission()) {
            return new Decision(true, -1, Duration.ZERO); // fail open without waiting for Redis
        }
        long start = System.nanoTime();
        try {
            @SuppressWarnings("unchecked")
            List<Long> result = redis.execute(SCRIPT, List.of(KEY_PREFIX + cardFingerprint),
                    String.valueOf(properties.capacity()), String.valueOf(properties.refillEvery().toMillis()));
            if (result == null || result.size() != 3) {
                throw new IllegalStateException("unexpected rate limiter result " + result);
            }
            breaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            return new Decision(result.get(0) == 1L, result.get(1), Duration.ofMillis(result.get(2)));
        } catch (DataAccessException | IllegalStateException e) {
            breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
            log.warn("card rate limiter unavailable, allowing the attempt (fail open): {}", e.getMessage());
            return new Decision(true, -1, Duration.ZERO);
        }
    }
}
