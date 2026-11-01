package org.pm.apigateway.ratelimit;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

/**
 * Spring Cloud Gateway's RedisRateLimiter with a circuit breaker in front of Redis (Day 23 chaos finding, ADR 0025).
 *
 * The plain limiter already fails OPEN when Redis is down, but only after each call's 200 ms command timeout, and it
 * logs a stack trace every time: in the Redis-down chaos run that was +200 ms on every request (checkout p95 0.13 s ->
 * 1.05 s with billing's card limiter) and 949 stack traces. With the breaker: after the first failures (>= 50% of the
 * last 10 calls, at least 5) Redis is skipped for {@code openFor} and requests are allowed at once; then 2 trial calls
 * decide whether it is back.
 *
 * How a failure is seen: RedisRateLimiter swallows Redis errors itself and answers "allowed, -1 tokens left"
 * (X-RateLimit-Remaining: -1); the Lua script never returns a negative count. So headers must stay on.
 * Skipped checks answer the same way (-1), so a client can't tell, and neither can it game it.
 */
public class BreakerRedisRateLimiter extends RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(BreakerRedisRateLimiter.class);
    private static final String UNKNOWN = "-1";

    private final CircuitBreaker breaker;

    public BreakerRedisRateLimiter(ReactiveStringRedisTemplate redis, RedisScript<List<Long>> script,
                                   ConfigurationService configurationService, Duration openFor) {
        super(redis, script, configurationService);
        setIncludeHeaders(true); // the -1 header is how a Redis failure is recognised
        this.breaker = CircuitBreaker.of("rate-limiter-redis", CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(openFor)
                .permittedNumberOfCallsInHalfOpenState(2)
                .build());
        breaker.getEventPublisher().onStateTransition(event -> log.warn(
                "rate limiter Redis breaker {} (open = limits not enforced, requests allowed without asking Redis)",
                event.getStateTransition()));
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        if (!breaker.tryAcquirePermission()) {
            Config config = getConfig().getOrDefault(routeId, new Config());
            return Mono.just(new Response(true, getHeaders(config, -1L))); // fail open, without the 200 ms wait
        }
        long start = System.nanoTime();
        return super.isAllowed(routeId, id)
                .doOnNext(response -> {
                    long took = System.nanoTime() - start;
                    if (UNKNOWN.equals(response.getHeaders().get(getRemainingHeader()))) {
                        breaker.onError(took, TimeUnit.NANOSECONDS, new IllegalStateException("rate limiter Redis call failed"));
                    } else {
                        breaker.onSuccess(took, TimeUnit.NANOSECONDS);
                    }
                })
                .doOnCancel(breaker::releasePermission);
    }

    public CircuitBreaker breaker() {
        return breaker;
    }
}
