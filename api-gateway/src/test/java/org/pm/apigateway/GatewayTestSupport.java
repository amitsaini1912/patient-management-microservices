package org.pm.apigateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Two tiny JDK HTTP servers stand in for the services behind the gateway:
 *   auth    GET /validate: "Bearer valid-<user>" -> 200 {"subject":"<user>","role":"USER"}, anything else -> 401
 *   backend every other path: 200 and remembers what it received (path + headers), playing billing / card-vault
 * The servers and their state are STATIC, shared by every test class in the JVM: {@link #resetStubs()} puts them back
 * before each test of every subclass. (CI once ran GatewayIntegrationTest before RedisDownTest, and a "broken
 * auth-service" left over from the last test made RedisDownTest get 503 - test order is not guaranteed.)
 */
abstract class GatewayTestSupport {

    static final String INVALID_TOKEN = "not-a-valid-token";

    record Received(String method, String path, Map<String, List<String>> headers) {
        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().get(0)).findFirst().orElse(null);
        }
    }

    static final HttpServer auth = server();
    static final HttpServer backend = server();
    static final List<Received> received = new CopyOnWriteArrayList<>();
    static volatile int authStatusOverride = 0; // e.g. 500: auth-service broken

    static {
        auth.createContext("/validate", exchange -> {
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            if (authStatusOverride != 0) {
                respond(exchange, authStatusOverride, "");
            } else if (header != null && header.startsWith("Bearer valid-")) {
                String user = header.substring("Bearer valid-".length());
                respond(exchange, 200, "{\"subject\":\"" + user + "\",\"role\":\"USER\"}");
            } else {
                respond(exchange, 401, "");
            }
        });
        auth.start();
        backend.createContext("/", exchange -> {
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    Map.copyOf(exchange.getRequestHeaders())));
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "{\"ok\":true}");
        });
        backend.start();
    }

    @BeforeEach
    void resetStubs() {
        received.clear();
        authStatusOverride = 0;
    }

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        registry.add("auth.service.url", () -> url(auth));
        registry.add("BILLING_SERVICE_URL", () -> url(backend));
        registry.add("CARD_VAULT_URL", () -> url(backend));
        registry.add("PATIENT_SERVICE_URL", () -> url(backend));
        registry.add("CARD_VAULT_KEY_GATEWAY", () -> "gateway-vault-key");
    }

    static String url(HttpServer server) {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static HttpServer server() {
        try {
            return HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
