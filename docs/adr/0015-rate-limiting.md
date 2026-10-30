# ADR 0015 — Rate limiting: per user at the gateway, per card in billing; callers identified from the JWT

- Status: Accepted
- Date: 2026-10-30

## Context
Nothing limited how often a caller could hit the API. Two abuse patterns matter for a payments system:
- one user (or a stolen session) hammering the API — needs a limit **per user**;
- **card testing**: trying one card number again and again (or many cards) to find out which ones work. Schemes
  also fine merchants for excessive retries on the same card. That needs a limit **per card**.
The gateway only asked auth-service "is this token valid?" (200/401); it didn't know *who* the caller was. And
billing's Idempotency-Keys shared one global scope "until the gateway forwards a caller id" (ADR 0007).

## Decision
1. **auth-service `GET /validate`** now returns `200 {"subject": "<email>", "role": "<role>"}` (was an empty 200).
   A missing Authorization header is 401 (was 400).
2. **api-gateway identifies the caller**: JwtValidation sets `X-User-Id` (subject) and `X-User-Role` on the
   request to the service. A global filter first **removes any copy the client sent**, on every route.
   Trust boundary: services must only be reachable through the gateway (k8s: ClusterIP; compose ports are dev-only).
3. **Per-user limit at the gateway**: Spring Cloud Gateway's built-in `RequestRateLimiter` (token bucket in Redis,
   key = `X-User-Id`, one bucket per route + user), placed AFTER JwtValidation:
   | Route | refill | burst | env |
   |---|---|---|---|
   | `/api/payments/**` | 5/s | 10 | `GATEWAY_PAYMENTS_RATE_PER_SECOND`, `GATEWAY_PAYMENTS_BURST` |
   | `POST /api/cards` | 1/s | 3 | `GATEWAY_CARDS_RATE_PER_SECOND`, `GATEWAY_CARDS_BURST` |
   Empty bucket → 429 + `X-RateLimit-*`; a global filter adds **`Retry-After`** = ceil(requested / rate) seconds
   (the built-in limiter doesn't). The built-in Lua script reads Redis `TIME` in whole seconds, so tokens come back
   in 1-second steps.
4. **Per-card limit in billing** (not at the gateway: the card is in the JSON body, and a gateway filter shouldn't
   parse payment bodies). Our own Lua script `ratelimit/card_token_bucket.lua`: one atomic check-and-take, Redis
   `TIME` in ms, fractional refill. Default **5 attempts, then 1 per minute** (`billing.rate-limit.card.*`).
   - Keyed by the card **fingerprint**, not the token (the plan said token): the vault issues a new token per
     tokenization (ADR 0013), so a per-token limit is dodged by re-tokenizing the same card.
   - Taken only for a NEW authorization, after the vault lookup and before anything is saved or charged.
     Declines count too (card testing is mostly declines). Idempotent replays and retries of our own CREATED
     row are free.
   - 429 problem + `Retry-After`; the Idempotency-Key is **released** (like 503), so the same key works later.
5. **Both limiters fail OPEN**: Redis down → requests go through, a warning is logged. A broken limiter must not
   take payments down. **Both** gateway and billing set Redis timeouts to 200 ms: when a working connection breaks,
   Lettuce queues commands until the command timeout (default 60 s) — found by hand: one payment through the
   gateway took 120 s with Redis stopped (two limited routes × 60 s); now ~0.5 s. Redis is excluded from billing's
   health check.
6. **Idempotency scope per caller** (closes ADR 0007's open point): scope = `user:` + first 32 hex of
   SHA-256(X-User-Id) (fits the column, no email in the table); no header → `default` as before. Payment/refund ids
   are derived from `scope|key`, so two users' equal keys are two payments; in the default scope ids are unchanged.
7. **Weakness #7 checked, not true as written**: a token rejected by auth-service already gave 401. The real bug was
   auth-service *broken/unreachable* → 500; now **503**. Error handling moved into the filter; the
   `@RestControllerAdvice` (`JwtValidationException`) was removed.

## Consequences
- Redis is now needed by gateway and billing (compose `redis`, k8s `redis` Service already existed for
  patient-service's cache).
- Not done: per-IP limit for unauthenticated routes (**`/auth/login` brute force is not limited**), limits per
  merchant/tenant, rate-limit metrics/alerts (Day 20b), caching auth-service answers (every request still calls
  `/validate`), RBAC on X-User-Role (weakness #8), gateway routes for `/reconciliation` and `/fx`.
