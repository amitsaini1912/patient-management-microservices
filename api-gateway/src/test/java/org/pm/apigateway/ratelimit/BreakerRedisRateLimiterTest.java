package org.pm.apigateway.ratelimit;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The breaker against a fake Redis that can be switched off: how many calls really reach Redis while it is down, and
 * that the limits come back when it does. (RedisDownTest covers a real Redis dying under the running gateway.)
 */
class BreakerRedisRateLimiterTest {

    private static final String ROUTE = "billing-payments-route";

    private final AtomicBoolean redisUp = new AtomicBoolean(true);
    private final AtomicInteger redisCalls = new AtomicInteger();
    private BreakerRedisRateLimiter limiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void limiter() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyList())).thenAnswer(call -> Flux.defer(() -> {
            redisCalls.incrementAndGet();
            return redisUp.get()
                    ? Flux.just(List.of(1L, 4L))                                  // allowed, 4 tokens left
                    : Flux.error(new RedisConnectionFailureException("Redis down"));
        }));
        RedisScript<List<Long>> script = mock(RedisScript.class);
        limiter = new BreakerRedisRateLimiter(redis, script, null, Duration.ofMillis(200));
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean(RedisRateLimiter.REDIS_SCRIPT_NAME, RedisScript.class, () -> script);
        context.refresh();
        limiter.setApplicationContext(context);
        limiter.getConfig().put(ROUTE, new RedisRateLimiter.Config().setReplenishRate(5).setBurstCapacity(10));
    }

    @Test
    void redisUpAnswersFromRedis() {
        Response response = ask();
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getHeaders()).containsEntry(RedisRateLimiter.REMAINING_HEADER, "4");
        assertThat(limiter.breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void redisDownOpensTheBreakerAfterFiveFailuresThenRedisIsNotAskedButRequestsAreAllowed() {
        redisUp.set(false);

        for (int i = 0; i < 50; i++) {
            Response response = ask();
            assertThat(response.isAllowed()).isTrue();                                       // fail open, always
            assertThat(response.getHeaders()).containsEntry(RedisRateLimiter.REMAINING_HEADER, "-1");
        }

        assertThat(redisCalls).hasValue(5);   // without the breaker: 50 calls, each waiting its timeout
        assertThat(limiter.breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void whenRedisIsBackTheTrialCallsCloseTheBreakerAndLimitsApplyAgain() throws InterruptedException {
        redisUp.set(false);
        for (int i = 0; i < 5; i++) {
            ask();
        }
        assertThat(limiter.breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        redisUp.set(true);
        Thread.sleep(300); // > openFor
        ask();
        ask();             // 2 successful trial calls

        assertThat(limiter.breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(ask().getHeaders()).containsEntry(RedisRateLimiter.REMAINING_HEADER, "4");
    }

    @Test
    void stillDownAfterTheWaitReopensAfterOneMoreTry() throws InterruptedException {
        redisUp.set(false);
        for (int i = 0; i < 5; i++) {
            ask();
        }
        Thread.sleep(300);
        int before = redisCalls.get();

        for (int i = 0; i < 20; i++) {
            ask();
        }

        assertThat(redisCalls.get() - before).isEqualTo(2); // the 2 trial calls, then open again
        assertThat(limiter.breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    private Response ask() {
        return limiter.isAllowed(ROUTE, "user-1").block(Duration.ofSeconds(5));
    }
}
