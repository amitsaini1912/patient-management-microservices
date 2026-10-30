package org.pm.apigateway.ratelimit;

import org.pm.apigateway.filter.IdentityHeaders;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
    KeyResolver userKeyResolver() {
        return exchange -> Mono.justOrEmpty(exchange.getRequest().getHeaders().getFirst(IdentityHeaders.USER_ID));
    }
}
