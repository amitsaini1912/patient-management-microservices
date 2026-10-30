package org.pm.apigateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The identity headers the services behind the gateway trust. Only the gateway may set them:
 * this global filter runs first on EVERY route and deletes whatever a client sent; JwtValidation then sets the
 * real values from auth-service's answer. (Trust boundary: the services must only be reachable through the gateway.)
 */
@Component
public class IdentityHeaders implements GlobalFilter, Ordered {

    public static final String USER_ID = "X-User-Id";
    public static final String USER_ROLE = "X-User-Role";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange.mutate()
                .request(r -> r.headers(h -> {
                    h.remove(USER_ID);
                    h.remove(USER_ROLE);
                }))
                .build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    static ServerWebExchange set(ServerWebExchange exchange, String userId, String role) {
        return exchange.mutate()
                .request(r -> r.headers(h -> {
                    h.set(USER_ID, userId);
                    if (role != null) {
                        h.set(USER_ROLE, role);
                    } else {
                        h.remove(USER_ROLE);
                    }
                }))
                .build();
    }
}
