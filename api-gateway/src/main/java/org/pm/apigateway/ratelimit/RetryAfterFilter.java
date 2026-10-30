package org.pm.apigateway.ratelimit;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * RequestRateLimiter answers 429 with X-RateLimit-* headers but no Retry-After, the standard header clients and
 * SDKs look for. Just before a 429 is sent, this adds it: seconds until enough tokens are back =
 * ceil(requested tokens / replenish rate), at least 1. Computed from the limiter's own headers, so the limits
 * stay configured in one place.
 */
@Component
public class RetryAfterFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpResponse response = exchange.getResponse();
        response.beforeCommit(() -> {
            HttpHeaders headers = response.getHeaders();
            if (HttpStatus.TOO_MANY_REQUESTS.equals(response.getStatusCode()) && !headers.containsKey(HttpHeaders.RETRY_AFTER)) {
                headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds(headers)));
            }
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    static long retryAfterSeconds(HttpHeaders headers) {
        long rate = number(headers.getFirst(RedisRateLimiter.REPLENISH_RATE_HEADER), 1);
        long requested = number(headers.getFirst(RedisRateLimiter.REQUESTED_TOKENS_HEADER), 1);
        return rate <= 0 ? 1 : Math.max(1, (requested + rate - 1) / rate);
    }

    private static long number(String value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1; // registered before any filter can commit the response
    }
}
