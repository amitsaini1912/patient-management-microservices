# ADR 0025 — Circuit breaker in front of the rate limiters' Redis

- Status: Accepted
- Date: 2026-11-01
- Amends: ADR 0015 (rate limits fail open). Evidence: `docs/CHAOS.md` (Redis stopped under k6).

## Context
Both rate limiters (gateway per user, billing per card) fail open when Redis is down, as decided in ADR 0015. But
each check still waited its 200 ms command timeout before giving up: with Redis stopped under load, checkout p95 went
from ~0.13 s to 1.05 s, and the gateway logged a full stack trace for each of 949 timeouts.

## Decision
A resilience4j circuit breaker (2.2.0, like billing's risk breaker, ADR 0017) in front of each limiter's Redis call:
- count window of the last 10 calls, at least 5, opens at >= 50% failures;
- open for 10 s (gateway: `gateway.rate-limit.redis-breaker.open-for`): requests are allowed AT ONCE without asking
  Redis; then 2 trial calls decide whether to close or reopen;
- one WARN per state change; gauges `gateway_ratelimit_redis_circuit_state` / `billing_ratelimit_card_circuit_state`
  (0 closed, 1 open, 2 half-open).

Gateway: `BreakerRedisRateLimiter` extends Spring Cloud Gateway's `RedisRateLimiter` and replaces the auto-configured
bean (same name, same `redis-rate-limiter.*` route args). RedisRateLimiter swallows Redis errors and answers
"allowed, X-RateLimit-Remaining: -1"; the Lua script never returns a negative count, so -1 is the failure signal
(headers are forced on). A skipped check answers the same -1.

## Consequences
- **Measured 2026-11-01** (Redis stopped 60 s under k6): checkout p95 1,054 -> 66 ms, gateway stack traces 949 -> 17.
- During a Redis outage about 5 requests per instance wait for the timeout, then 2 per 10 s (trial calls), instead of
  every request. Not re-measured under k6 yet.
- While open, limits are not enforced at all (as before, but now for up to 10 s after Redis is back). The per-card
  limit is a fraud control: an attacker who can take Redis down gets unlimited card attempts for that time; the
  issuer's own checks and the risk engine remain (the risk engine also needs Redis, so it falls back - ADR 0017).
- Tests: gateway `BreakerRedisRateLimiterTest` (4) + `RedisDownTest` with a real Redis stopped (breaker opens, next
  request answered without the timeout); billing `CardRateLimiterFailOpenTest` (+1: 30 attempts, 5 reach Redis, limit
  enforced again after it returns).
