package org.pm.billingservice.ratelimit;

import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Per-card limit on authorization attempts (ADR 0015): a token bucket per card in Redis, checked and taken in one
 * Lua script (ratelimit/card_token_bucket.lua). Stops "card testing" - a fraudster trying one stolen card again and
 * again - and protects the issuer relationship (schemes fine merchants for excessive retries).
 *
 * The key is the card's FINGERPRINT, not its token: the vault hands out a new token for every tokenization
 * (ADR 0013), so a per-token limit could be dodged by simply tokenizing the same card again.
 *
 * Fails OPEN: if Redis is down or slow, the attempt is allowed and a warning logged. A broken limiter must not stop
 * every payment; the gateway's per-user limit and the issuer's own checks still apply.
 */
@Component
@EnableConfigurationProperties(CardRateLimitProperties.class)
public class CardRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(CardRateLimiter.class);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT =
            RedisScript.of(new ClassPathResource("ratelimit/card_token_bucket.lua"), List.class);
    private static final String KEY_PREFIX = "billing:ratelimit:card:";

    /** @param retryAfter how long until the next attempt is allowed; zero when allowed */
    public record Decision(boolean allowed, long remaining, Duration retryAfter) {}

    private final StringRedisTemplate redis;
    private final CardRateLimitProperties properties;

    public CardRateLimiter(StringRedisTemplate redis, CardRateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** Takes one attempt for this card, or throws {@link CardRateLimitedException}. */
    public void acquire(String cardFingerprint) {
        if (!properties.enabled()) {
            return;
        }
        Decision decision = tryAcquire(cardFingerprint);
        if (!decision.allowed()) {
            log.info("card {}... rate-limited, retry in {}", cardFingerprint.substring(0, Math.min(8, cardFingerprint.length())),
                    decision.retryAfter());
            throw new CardRateLimitedException(decision.retryAfter());
        }
    }

    Decision tryAcquire(String cardFingerprint) {
        try {
            @SuppressWarnings("unchecked")
            List<Long> result = redis.execute(SCRIPT, List.of(KEY_PREFIX + cardFingerprint),
                    String.valueOf(properties.capacity()), String.valueOf(properties.refillEvery().toMillis()));
            if (result == null || result.size() != 3) {
                throw new IllegalStateException("unexpected rate limiter result " + result);
            }
            return new Decision(result.get(0) == 1L, result.get(1), Duration.ofMillis(result.get(2)));
        } catch (DataAccessException | IllegalStateException e) {
            log.warn("card rate limiter unavailable, allowing the attempt (fail open): {}", e.getMessage());
            return new Decision(true, -1, Duration.ZERO);
        }
    }
}
