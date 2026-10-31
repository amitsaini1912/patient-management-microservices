package org.pm.apigateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;

/**
 * Day 23 (chaos): a service the gateway can't CONNECT to (down, or hung with its accept queue full) was a 500 after
 * Netty's 30 s connect timeout. Now the connect timeout is 2 s (application.yml) and the answer is 503 + Retry-After:
 * nothing reached the service, so retrying is always safe. (No answer in time after connecting = 504, Spring Cloud
 * Gateway's own mapping of the response timeout.)
 */
@Component
public class UpstreamUnavailableFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(UpstreamUnavailableFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).onErrorMap(UpstreamUnavailableFilter::cannotConnect, e -> {
            log.warn("{} {}: upstream unreachable ({})", exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(), e.toString());
            exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "service unavailable, retry later", e);
        });
    }

    /** io.netty.channel.ConnectTimeoutException and "connection refused" are both ConnectExceptions. */
    static boolean cannotConnect(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ConnectException) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;   // wraps the routing filter, which runs last
    }
}
