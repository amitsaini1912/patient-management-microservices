package org.pm.apigateway.ratelimit;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.pm.apigateway.filter.IdentityHeaders;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

/**
 * Per-user rate limiting (Day 12, ADR 0015) with Spring Cloud Gateway's built-in RequestRateLimiter: a token bucket
 * per (route, user) in Redis, refilled every second; each request takes one token, an empty bucket -> 429.
 * The limits are per route in application.yml.
 */
@Configuration
public class RateLimitConfig {

    /**
     * The user = X-User-Id, set by JwtValidation from auth-service's answer (never the client's own header).
     * Only used on routes that have JwtValidation BEFORE RequestRateLimiter. No user -> empty key -> the limiter
     * refuses (deny-empty-key, 403) instead of putting every anonymous caller into one shared bucket.
     */
    @Bean
    @Primary // the default for RequestRateLimiter; clientIpKeyResolver is named per route
    KeyResolver userKeyResolver() {
        return exchange -> Mono.justOrEmpty(exchange.getRequest().getHeaders().getFirst(IdentityHeaders.USER_ID));
    }

    /**
     * The client's IP, for routes without a user yet (/auth/login, ADR 0027). By default the TCP peer address:
     * X-Forwarded-For is set by whoever sends the request, so trusting it would give an attacker a fresh bucket per
     * request. Behind N proxies we control (ingress, load balancer), set gateway.trusted-proxies=N: the address N hops
     * from the right of X-Forwarded-For is used - the one our first proxy saw.
     */
    @Bean
    public KeyResolver clientIpKeyResolver(@Value("${gateway.trusted-proxies:0}") int trustedProxies) {
        if (trustedProxies <= 0) {
            return exchange -> Mono.justOrEmpty(exchange.getRequest().getRemoteAddress())
                    .map(address -> address.getAddress().getHostAddress());
        }
        XForwardedRemoteAddressResolver resolver = XForwardedRemoteAddressResolver.maxTrustedIndex(trustedProxies);
        return exchange -> Mono.justOrEmpty(resolver.resolve(exchange)).map(address -> address.getAddress().getHostAddress());
    }

    /**
     * Replaces the auto-configured RedisRateLimiter (same bean name, same redis-rate-limiter.* route args) with the
     * breaker version. Gauge gateway_ratelimit_redis_circuit_state: 0 closed, 1 open, 2 half-open (like billing's
     * risk breaker).
     */
    @Bean
    RedisRateLimiter redisRateLimiter(ReactiveStringRedisTemplate redis,
                                      @Qualifier(RedisRateLimiter.REDIS_SCRIPT_NAME) RedisScript<List<Long>> script,
                                      ConfigurationService configurationService,
                                      @Value("${gateway.rate-limit.redis-breaker.open-for:10s}") Duration openFor,
                                      MeterRegistry meters) {
        BreakerRedisRateLimiter limiter = new BreakerRedisRateLimiter(redis, script, configurationService, openFor);
        Gauge.builder("gateway.ratelimit.redis.circuit.state", limiter, l -> switch (l.breaker().getState()) {
                    case CLOSED -> 0;
                    case OPEN -> 1;
                    case HALF_OPEN -> 2;
                    default -> 3;
                }).description("Rate limiter Redis breaker: 0 closed, 1 open (limits not enforced), 2 half-open")
                .register(meters);
        return limiter;
    }
}
