package org.pm.billingservice.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Idempotency-Key for every POST under /payments (all of them move money or holds).
 *
 *   no key / bad key                      -> 400
 *   new key                               -> run the request, store the response (unless 5xx / retryable)
 *   same key, same request, finished      -> replay the stored response (+ Idempotent-Replayed: true)
 *   same key, same request, still running -> 409 (+ Retry-After); after the lock timeout a retry takes over
 *   same key, different request           -> 422
 *
 * The filter only stops DUPLICATE REQUESTS. Re-running a request after a crash or 5xx is safe only because
 * the payment code below is idempotent too (ids derived from the key, processor calls keyed) - see PaymentService.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";
    /** Set by an exception handler when the error is temporary: the key is released instead of storing the error. */
    public static final String RETRYABLE_ATTRIBUTE = IdempotencyFilter.class.getName() + ".retryable";
    /**
     * Who is calling (Day 12): api-gateway sets X-User-Id from the validated JWT and removes any copy the client
     * sent. Each user gets their own key space: user A's "order-1" and user B's "order-1" are different requests,
     * and nobody can replay someone else's response by guessing their key. (Trust boundary: billing must only be
     * reachable through the gateway; a direct caller could claim any X-User-Id.)
     */
    public static final String USER_HEADER = "X-User-Id";
    /** The scope this request ran in, for {@link #serviceKey}. */
    public static final String SCOPE_ATTRIBUTE = IdempotencyFilter.class.getName() + ".scope";
    /**
     * No X-User-Id (internal callers, tests, anything not through the gateway): one shared scope, as before Day 12.
     * Keys there must be globally unique (UUIDs). See ADR 0007.
     */
    static final String DEFAULT_SCOPE = "default";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9_\\-:.]{8,255}");

    private final IdempotencyStore store;
    private final IdempotencyProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public IdempotencyFilter(IdempotencyStore store, IdempotencyProperties properties, Clock clock, ObjectMapper objectMapper) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key == null || !VALID_KEY.matcher(key).matches()) {
            writeProblem(response, HttpStatus.BAD_REQUEST, "Missing or invalid Idempotency-Key",
                    "send a unique Idempotency-Key header (8-255 chars of A-Z a-z 0-9 _ - : .), e.g. a UUID");
            return;
        }

        String scope = scopeOf(request);
        request.setAttribute(SCOPE_ATTRIBUTE, scope);
        byte[] body = request.getInputStream().readAllBytes();
        String hash = requestHash(request.getMethod(), request.getRequestURI(), body);
        // micros: what Postgres stores, so this exact value can be matched again as our fencing token
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        if (store.tryClaim(scope, key, hash, now, now.plus(properties.ttl()))) {
            execute(scope, key, now, new CachedBodyRequest(request, body), response, chain);
            return;
        }

        Optional<IdempotencyRecord> existing = store.find(scope, key);
        if (existing.isEmpty()) {
            // released or expired between our INSERT and SELECT: tell the client to simply retry
            writeConflict(response);
            return;
        }
        IdempotencyRecord record = existing.get();
        if (!record.requestHash().equals(hash)) {
            writeProblem(response, HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency-Key reused",
                    "this Idempotency-Key was already used for a different request");
            return;
        }
        if (record.status() == IdempotencyRecord.Status.COMPLETED) {
            replay(record, response);
            return;
        }
        if (store.tryTakeOver(scope, key, hash, now.minus(properties.lockTimeout()), now)) {
            log.warn("taking over abandoned idempotency key {} (locked since {})", key, record.lockedAt());
            execute(scope, key, now, new CachedBodyRequest(request, body), response, chain);
            return;
        }
        writeConflict(response);
    }

    /**
     * "user:" + the first 32 hex chars of SHA-256(X-User-Id): fits the 100-char column whatever the id, and keeps
     * the user's email out of idempotency rows. No header -> {@link #DEFAULT_SCOPE}.
     */
    static String scopeOf(HttpServletRequest request) {
        String user = request.getHeader(USER_HEADER);
        if (user == null || user.isBlank()) {
            return DEFAULT_SCOPE;
        }
        return "user:" + sha256Hex(user.getBytes(StandardCharsets.UTF_8)).substring(0, 32);
    }

    /**
     * The key PaymentService derives payment/refund ids from: the client's key within its scope. Default scope ->
     * the key itself (so ids are the same as before Day 12); a user's scope -> "scope|key", so two users' equal keys
     * become two different payments.
     */
    public static String serviceKey(HttpServletRequest request, String idempotencyKey) {
        Object scope = request.getAttribute(SCOPE_ATTRIBUTE);
        String effective = scope != null ? scope.toString() : scopeOf(request);
        return DEFAULT_SCOPE.equals(effective) ? idempotencyKey : effective + "|" + idempotencyKey;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /** SHA-256 over method, path and the exact body bytes. Public so tests can build matching rows. */
    public static String requestHash(String method, String path, byte[] body) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update((method + " " + path + "\n").getBytes(StandardCharsets.UTF_8));
            sha256.update(body);
            return HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /** {@code lockedAt} = the value we claimed the key with; only that owner may complete or release it. */
    private void execute(String scope, String key, Instant lockedAt, HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingResponseWrapper cachingResponse = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, cachingResponse);
        } catch (IOException | ServletException | RuntimeException e) {
            store.release(scope, key, lockedAt); // unhandled error: nothing worth replaying, let the client retry
            throw e;
        }

        int status = cachingResponse.getStatus();
        if (status >= 500 || Boolean.TRUE.equals(request.getAttribute(RETRYABLE_ATTRIBUTE))) {
            // temporary failure: a retry with the SAME key must run again, not replay this error
            store.release(scope, key, lockedAt);
        } else {
            // 2xx and deterministic 4xx (400, 404, 409 wrong state, 422) are stored and replayed
            boolean stored = store.complete(scope, key, lockedAt, status, cachingResponse.getContentType(),
                    cachingResponse.getHeader(HttpHeaders.LOCATION), cachingResponse.getContentAsByteArray());
            if (!stored) {
                // we ran past the lock timeout and a retry took over; its response wins, ours still goes to our client
                log.warn("idempotency key {} was taken over while we ran; response not stored", key);
            }
        }
        cachingResponse.copyBodyToResponse();
    }

    private static void replay(IdempotencyRecord record, HttpServletResponse response) throws IOException {
        response.setStatus(record.responseStatus());
        if (record.responseContentType() != null) {
            response.setContentType(record.responseContentType());
        }
        if (record.responseLocation() != null) {
            response.setHeader(HttpHeaders.LOCATION, record.responseLocation());
        }
        response.setHeader(REPLAYED_HEADER, "true");
        if (record.responseBody() != null) {
            response.getOutputStream().write(record.responseBody());
        }
    }

    private void writeConflict(HttpServletResponse response) throws IOException {
        response.setHeader(HttpHeaders.RETRY_AFTER, "1");
        writeProblem(response, HttpStatus.CONFLICT, "Request in progress",
                "a request with this Idempotency-Key is still being processed; retry shortly");
    }

    private void writeProblem(HttpServletResponse response, HttpStatus status, String title, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    /** We had to read the body to hash it; this lets the controller read it again. */
    private static class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return in.read(); }
                @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
                @Override public boolean isFinished() { return in.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
            };
        }
    }
}
