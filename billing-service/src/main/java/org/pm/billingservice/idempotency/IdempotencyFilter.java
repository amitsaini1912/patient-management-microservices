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
     * Until the gateway forwards a caller id, every client shares one scope. Keys must therefore be
     * globally unique (UUIDs); a guessable key could replay another client's response. See ADR 0007.
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

        byte[] body = request.getInputStream().readAllBytes();
        String hash = requestHash(request.getMethod(), request.getRequestURI(), body);
        // micros: what Postgres stores, so this exact value can be matched again as our fencing token
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        if (store.tryClaim(DEFAULT_SCOPE, key, hash, now, now.plus(properties.ttl()))) {
            execute(key, now, new CachedBodyRequest(request, body), response, chain);
            return;
        }

        Optional<IdempotencyRecord> existing = store.find(DEFAULT_SCOPE, key);
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
        if (store.tryTakeOver(DEFAULT_SCOPE, key, hash, now.minus(properties.lockTimeout()), now)) {
            log.warn("taking over abandoned idempotency key {} (locked since {})", key, record.lockedAt());
            execute(key, now, new CachedBodyRequest(request, body), response, chain);
            return;
        }
        writeConflict(response);
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
    private void execute(String key, Instant lockedAt, HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingResponseWrapper cachingResponse = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, cachingResponse);
        } catch (IOException | ServletException | RuntimeException e) {
            store.release(DEFAULT_SCOPE, key, lockedAt); // unhandled error: nothing worth replaying, let the client retry
            throw e;
        }

        int status = cachingResponse.getStatus();
        if (status >= 500 || Boolean.TRUE.equals(request.getAttribute(RETRYABLE_ATTRIBUTE))) {
            // temporary failure: a retry with the SAME key must run again, not replay this error
            store.release(DEFAULT_SCOPE, key, lockedAt);
        } else {
            // 2xx and deterministic 4xx (400, 404, 409 wrong state, 422) are stored and replayed
            boolean stored = store.complete(DEFAULT_SCOPE, key, lockedAt, status, cachingResponse.getContentType(),
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
