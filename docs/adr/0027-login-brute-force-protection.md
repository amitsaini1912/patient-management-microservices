# ADR 0027 — Login brute-force protection: per-IP limit at the gateway, account lockout in auth-service

- Status: Accepted
- Date: 2026-11-01
- Closes the Day 12 gap (ADR 0015 limited only logged-in users): `/auth/login` had no limit at all.

## Context
Anyone could try passwords against `/auth/login` as fast as auth-service answered, from one machine or many. Also,
a login for an unknown email skipped bcrypt and answered much faster than a wrong password: the response time told
an attacker which emails have accounts.

## Decision
Two layers, because each stops what the other can't:

**Gateway, per client IP** (`auth-login-route`, before the general `/auth/**` route): a burst of 10 attempts, then 1
every 6 s; over that 429 + `Retry-After: 6`. RedisRateLimiter refills per second, so each login takes 6 tokens of a
60-token bucket refilled at 1/s. Key = the TCP peer address; `X-Forwarded-For` is ignored unless
`gateway.trusted-proxies=N` says N proxies we run are in front (then the address N hops from the right is used, the
one our first proxy saw). Shares the Redis breaker of ADR 0025 (Redis down = not enforced).

**auth-service, per account**: 5 wrong passwords within 15 minutes of the first lock the account for 15 minutes
(`auth.login.*`). While locked, every login is refused - the right password too - with the SAME 401 as a wrong
password (no "locked" answer to probe which accounts exist); attempts during the lock don't extend it. A right
password resets the count. Counted under a row lock, after the slow bcrypt check, with the user re-read under the
lock (`refresh(PESSIMISTIC_WRITE)`): the first version used a locked find, and with open-session-in-view it returned
the copy already loaded in the request - 10 concurrent wrong passwords were lost and the account never locked (found
by the test; open-in-view is now also off).

**Same cost for unknown emails**: one bcrypt check per login always - against a dummy hash for an unknown email. The
encoder's cost is set to 12, the cost of the stored hashes (it was the default 10: a dummy at 10 would still be faster).

Metric `auth_login_attempts_total{outcome=success|bad_password|locked_now|locked|unknown_user}`.

## Alternatives
- Limit per email at the gateway: needs the request body in the gateway (buffering every login); the account lock in
  auth-service does that job with the database it already has.
- 429 "account locked": tells an attacker the account exists and is under attack. Same 401 instead; support sees it in
  the log (`account … locked`) and the metric.
- CAPTCHA / exponential backoff per account: more client work; 15-minute lock is the common baseline.

## Consequences
- **Lockout is also a weapon**: 5 wrong passwords lock someone else's account for 15 minutes. Accepted (short lock,
  per-IP limit slows it down); a real system would add a second factor or a reset link instead of a hard lock.
- Behind NAT, a cloud load balancer, or Docker's localhost port proxy, all clients can arrive from ONE address: then
  10 logins per 6 s are shared by everyone until `gateway.trusted-proxies` and the proxy's X-Forwarded-For are set.
- No alert yet on `locked_now` spikes.
- Tests: auth-service `LoginLockoutTest` (4: lock after 5 incl. right password refused, lock runs out after 15 min on a
  test clock, failures outside the window forgotten, 10 concurrent wrong passwords -> locked, unknown email takes as
  long as a wrong password - fails without the dummy hash); gateway `LoginRateLimitTest` (3: 10 attempts then 429 +
  Retry-After 6, forged X-Forwarded-For doesn't get a new bucket, other auth routes unaffected, trusted-proxy key).
