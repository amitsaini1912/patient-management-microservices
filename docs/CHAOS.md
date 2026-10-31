# Chaos (Day 23): what happens when something breaks

Every number below was measured on 2026-10-31 (same laptop as `PERFORMANCE.md`). Two kinds of evidence:

1. **Automated tests** that break something and assert what must still hold. They run in CI.
2. **Live runs**: `load-test/chaos.sh <scenario>` against the full stack (`load-test/start-stack.sh`) while k6
   sends a steady **5 checkouts/s** for 150 s. The fault starts at t=30 s and is healed at t=90 s (database: t=60 s).
   The k6 client behaves like a good client: on 503, 409 + Retry-After or gRPC UNAVAILABLE it waits and retries the
   **same** request with the **same** Idempotency-Key, up to 6 attempts. Files: `load-test/results/chaos-<scenario>*`
   (`-outcomes.txt` = how each checkout ended, `-timeline.txt` = billing's gauges every 5 s, `-server.txt` = server
   diff, `.json` = k6 summary).

What must hold whatever breaks: **no money moves twice**, every payment ends in one clear state, the client is told
whether to retry, and the system **recovers by itself** when the fault is gone (no restarts).

## Summary

| Fault (60 s unless noted) | Checkouts | What the client saw | Recovery | Verdict |
|---|---|---|---|---|
| Kafka stopped | 751 / 751 captured | nothing: p95 132 ms, 0 errors | outbox held 936 events (oldest 62 s), sent within 5 s of Kafka's return | ✅ as designed |
| Risk engine hung (`docker pause`) | 750 / 750 captured | nothing: p95 96 ms, 0 errors | 14 checks paid the 300 ms budget, then the breaker opened (301 fallbacks); half-open → closed by itself | ✅ as designed |
| billing's Postgres stopped (30 s) | 673 / 673 started checkouts captured | gRPC account creation answered UNAVAILABLE, 52 client retries; 0 failed HTTP requests | Hikari replaced dead connections; no restart | ✅ **after 3 fixes** |
| Redis stopped | 750 / 750 captured | 0 errors, but checkout p95 1.05 s (was ~0.13 s) | limiters failed open; risk engine 503 (its features live in Redis) → breaker → fallback | ✅ works, ⚠ slow (see below) |
| card-vault hung (SIGSTOP) | 502 captured; checkouts during the freeze failed fast | before: tokenize hung up to **60 s**, 27 × 500, 128 dropped. After: 503 in 1 s / 504 in 3 s, 0 × 500, 6 dropped | back to normal when unfrozen | ✅ **after fix** |
| Acquirer (simulator) hung (SIGSTOP) | 534 captured | before: 292 × **502 "refused"**. After: 503 "outcome unknown, retry with the same key", 1,126 retries | late answers healed payments via webhooks | ⚠ **partly fixed**: orphan holds 102 → 59 |

## Automated tests (Day 23)

| Test | Breaks | Asserts |
|---|---|---|
| billing `DatabaseChaosIntegrationTest` (Toxiproxy between billing and Postgres) | Postgres restart (all pooled connections cut) | never a 500; at most one 503 per pooled connection (≤ 10); each retry with the same key → exactly one payment, acquirer called once |
| | database down | 503 + Retry-After in < 5 s (Hikari connection timeout), nothing charged; same key works when it's back |
| | connection lost **after the acquirer charged** | 503; the retry reuses the hold (acquirer called once), one payment row, one outbox event, ledger balanced |
| billing `DatabaseErrorsTest` | | what counts as "database unavailable", by cause (see fix 1) |
| billing `BillingGrpcServiceDatabaseDownTest` | | database down → gRPC UNAVAILABLE; other errors are not dressed up as temporary |
| billing `PaymentControllerTest` / `SimulatorPaymentProcessorTest` | acquirer timeout / unreachable | 503 + Retry-After, no internal detail leaked; an acquirer that answered 5xx stays 502 |
| api-gateway `UpstreamFailureTest` | a hung service; an unreachable one | 504 after the response timeout (not an endless wait); 503 + Retry-After when it can't connect (was 500) |

Already covered by earlier days and not repeated: Kafka down (`OutboxIntegrationTest`, Day 6), risk engine down or slow
(`RiskCheckIntegrationTest`, Day 19), duplicate / out-of-order webhooks (`GatewayWebhookIntegrationTest`, Day 7),
Redis down (`CardRateLimiterFailOpenTest`, gateway `RedisDownTest`, Day 12).

## Findings and fixes

**1. A database outage was a 500, and a Postgres restart broke the next requests.** Toxiproxy test, then live.
- Hikari skips its liveness check for a connection used in the last 500 ms. Under traffic every pooled connection is
  that fresh, so after a restart each dead connection fails one request.
- The client got **500**: "something broke", not "retry".
- Errors also escaped in three places that the controller advice never sees: the idempotency filter (its key table is
  in the same database), a failed **rollback** on the dead connection (Spring then throws a generic
  `JpaSystemException` "Connection is closed" and hides the original error), and gRPC (grpc-java answers `UNKNOWN`).
- Live, Postgres also sends `57P01` "terminating connection due to administrator command" while shutting down.
- Fix: `DatabaseErrors.isUnavailable()` decides by **cause** (SQL state class `08` / `57P`, closed connection,
  EOF/socket errors, Spring's resource-failure types). It's used by the REST exception handler and the idempotency
  filter (→ 503 + Retry-After, key released; if the release fails too, the key frees itself after the 60 s lock
  timeout) and by gRPC (→ UNAVAILABLE).
- Chosen over validating every connection on every borrow (`aliveBypassWindowMs=0`). That would cost a database
  round trip on each of the ~13 connection checkouts per checkout, to save at most 10 retried requests per restart.

**2. The gateway had no timeouts.** Live, card-vault frozen.
- A hung service held every client for as long as the **client** would wait: tokenize p95 54 s, max 60 s.
- Clients piled up: 128 checkouts couldn't even start.
- A full accept queue gave **500** after Netty's 30 s connect timeout.
- Fix: connect timeout 2 s, response timeout 10 s (billing's slowest legitimate authorize is ~7.3 s: risk 0.3 +
  vault 2 + simulator 5), tokenize route 1 s / 3 s.
- `UpstreamUnavailableFilter`: can't connect → **503 + Retry-After**; the response timeout → **504** (Spring Cloud
  Gateway's own mapping).
- After: tokenize 503 in 1 s or 504 in 3 s, 0 × 500, 6 dropped.

**3. An acquirer timeout said "refused" (502), and clients gave up on payments that went through.** Live, simulator frozen.
- Billing's read timeout (5 s) means the outcome is unknown: the acquirer may still do it, and here it did. When the
  simulator resumed it processed the queued requests, and its webhooks healed the payments to AUTHORIZED.
- First run: **102 payments AUTHORIZED but never captured**. Their clients had been told the payment failed, while
  the money was held on the card (released only by the 7-day authorization expiry). Plus 190 left in CREATED
  (no money moved, but never resolved).
- Fix: the processor's "no answer" (timeout / connection refused) is its own error kind →
  **503 + Retry-After, "the outcome is unknown, retry with the same Idempotency-Key"**. The retry reaches the acquirer
  with the same key and gets the original result, never a second charge (Day 5).
- After, with a client that retries for ~15 s: **59** orphans, 121 CREATED, 534 captured (was 458).
- **Still open:** a 60 s outage outlasts any reasonable client retry. Billing must resolve unknown outcomes itself:
  a job that asks the acquirer about stale CREATED payments by idempotency key and voids authorizations nobody came
  back for. Logged in PROGRESS open items.

**4. Smaller findings (not fixed today)**
- **Redis down → checkout p95 ~1 s.** Every rate-limit check waits its 200 ms timeout before failing open (gateway
  once, billing's card limit once more on authorize). A breaker around Redis would skip it after the first failures.
  The gateway also logged a full stack trace for each of the 949 timeouts.
- **Billing cold start opens the risk breaker.** Right after billing starts, the first risk checks miss the 300 ms
  budget (cold HTTP client / JIT): 7 timeouts → breaker open → ~30 s of fallback decisions. Every live run started
  this way after a restart. A warm-up call before readiness would avoid it.
- **Thundering herd after an outage.** When Postgres came back, the clients' queued retries arrived together:
  99 risk-check timeouts in one second, breaker open, fallback for ~30 s. Safe (fallback rules), but retries should
  have jitter (clients) and billing could limit concurrent risk calls.
- **k6 `dropped_iterations`** in the database / vault runs: k6 couldn't start new virtual users fast enough while
  many were sleeping in retry waits. A limitation of the client, not of the system.
