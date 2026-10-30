package org.pm.cardvault.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Who is calling? {@code X-Api-Key} must match one configured client (constant-time compare against each).
 * No/unknown key -> 401 here. Whether that client may do THIS operation is checked per endpoint (403).
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";
    public static final String CALLER = ApiKeyFilter.class.getName() + ".caller";

    public record Caller(String name, VaultProperties.Client client) {}

    private final Map<String, VaultProperties.Client> clients;

    public ApiKeyFilter(VaultProperties properties) {
        if (properties.clients() == null || properties.clients().isEmpty()) {
            throw new IllegalStateException("no vault clients configured");
        }
        properties.clients().forEach((name, client) -> {
            if (client.apiKey() == null || client.apiKey().length() < 16) {
                throw new IllegalStateException("vault client " + name + " needs an API key of at least 16 characters");
            }
        });
        this.clients = properties.clients();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/cards");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        Caller caller = null;
        if (header != null) {
            byte[] presented = header.getBytes(StandardCharsets.UTF_8);
            for (Map.Entry<String, VaultProperties.Client> e : clients.entrySet()) {
                // check every client (no early exit), so timing doesn't tell which key was close
                if (MessageDigest.isEqual(presented, e.getValue().apiKey().getBytes(StandardCharsets.UTF_8))) {
                    caller = new Caller(e.getKey(), e.getValue());
                }
            }
        }
        if (caller == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("{\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"missing or unknown API key\"}");
            return;
        }
        request.setAttribute(CALLER, caller);
        chain.doFilter(request, response);
    }
}
