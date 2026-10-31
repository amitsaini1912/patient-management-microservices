# ADR 0022 — Load testing the checkout path with k6

- Status: Accepted
- Date: 2026-10-31
- Results: `docs/PERFORMANCE.md`. Risk engine side: razorpay-risk-agent ADR 0010.

## Decision
- **k6** (`load-test/checkout.js`), one iteration = one checkout through the real path: gRPC account → tokenize at
  card-vault via the gateway → authorize (risk check, simulator, webhooks) → capture.
- **Open model** (arrival rate): load doesn't drop when the system slows down (no coordinated omission); a system
  that can't keep up shows as latency and `dropped_iterations`.
- **New patient + new card every iteration**: no velocity-rule or per-card-limit artefacts. The gateway's per-user
  limits are lifted for the single k6 user (configured by env, not changed in code).
- **Separate compose project `pm-loadtest`** with fresh databases and generated secrets (`load-test/.env.loadtest`,
  gitignored). Plain `java -jar`, no tracing. auth-service gets an `auth` database in billing-db (compose has no auth DB).
- **Server side measured too** (`load-test/server_stats.py`, Prometheus, `pg-waits.sh`): k6's latencies alone hid
  the main finding. Under overload billing's breaker sent 66% of payments to the fallback rules, with no errors.
  The load test report always includes billing's risk fallback counter.
- k6 and the system run on the same laptop; numbers are for this machine only and say so.

## Consequences
- Found: risk engine CPU per decision was the bottleneck (fixed: 15 → 30 checkouts/s with every payment risk-checked)
  and three false-positive / masking bugs in the engine's PAN guard.
- Not covered: multi-instance billing, network latency between hosts, long soak runs (hours), the patient-service path.
