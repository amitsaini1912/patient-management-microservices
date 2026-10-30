package org.pm.apigateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Asks auth-service whether the bearer token is valid. If it is, auth-service says who it belongs to (Day 12) and
 * the request goes on with that identity as headers for the services behind us (and for the rate limiter's key):
 *   X-User-Id   = the token's subject (the user's email)
 *   X-User-Role = its role
 * Any copy of those headers the client sent is removed first ({@link IdentityHeaders}), so they can't be forged.
 *
 *   no / malformed token, or auth-service says 401/403 -> 401
 *   auth-service broken or unreachable                  -> 503 (it's not the caller's fault; was a 500 before Day 12)
 */
@Component
public class JwtValidationGatewayFilterFactory extends
        AbstractGatewayFilterFactory<Object> {

    private static final Logger log = LoggerFactory.getLogger(JwtValidationGatewayFilterFactory.class);

    private final WebClient webClient;

    public JwtValidationGatewayFilterFactory(WebClient.Builder webClientBuilder,
                                             @Value("${auth.service.url}") String authServiceUrl) {
        this.webClient = webClientBuilder.baseUrl(authServiceUrl).build();
    }

    /** GET /validate's 200 body. */
    record Identity(String subject, String role) {}

    /** Either who the caller is, or the status to refuse them with. */
    private record Outcome(Identity identity, HttpStatus refusal) {
        static Outcome allowed(Identity identity) { return new Outcome(identity, null); }
        static Outcome refused(HttpStatus status) { return new Outcome(null, status); }
    }

    @Override
    public GatewayFilter apply(Object config) {
        return (exchange, chain) -> {
            String token =
                    exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

            if(token == null || !token.startsWith("Bearer ")) {
                return reject(exchange, HttpStatus.UNAUTHORIZED);
            }

            return webClient.get()
                    .uri("/validate")
                    .header(HttpHeaders.AUTHORIZATION, token)
                    .retrieve()
                    .bodyToMono(Identity.class)
                    .filter(identity -> identity.subject() != null && !identity.subject().isBlank())
                    .map(Outcome::allowed)
                    // 200 without an identity: an auth-service older than Day 12 - we can't tell who this is
                    .switchIfEmpty(Mono.fromSupplier(() -> Outcome.refused(HttpStatus.SERVICE_UNAVAILABLE)))
                    .onErrorResume(e -> Mono.just(Outcome.refused(statusFor(e))))
                    // only the auth call is covered by onErrorResume above, never the downstream chain
                    .flatMap(outcome -> outcome.identity() != null
                            ? chain.filter(IdentityHeaders.set(exchange, outcome.identity().subject(), outcome.identity().role()))
                            : reject(exchange, outcome.refusal()));
        };
    }

    private static HttpStatus statusFor(Throwable e) {
        if (e instanceof WebClientResponseException response
                && (response.getStatusCode().value() == 401 || response.getStatusCode().value() == 403)) {
            return HttpStatus.UNAUTHORIZED;
        }
        log.warn("auth-service unavailable, answering 503: {}", e.toString());
        return HttpStatus.SERVICE_UNAVAILABLE;
    }

    private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        if (status == HttpStatus.UNAUTHORIZED) {
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return exchange.getResponse().setComplete();
    }
}
