# Performance (Day 22: k6 load test)

Only measured numbers go in this file. Each one names the run (profile, rate, date) and the result file it came from
in `load-test/results/`. Nothing is estimated.

## What is measured

One iteration = one patient paying for a checkout, through the real path:

| Step | Request | Path |
|---|---|---|
| 1 | gRPC `CreateBillingAccount` | → billing :9001 (what patient-service does for a new patient) |
| 2 | `POST /api/cards` (new card) | gateway → auth `/validate` → card-vault |
| 3 | `POST /api/payments` (₹100–₹2,000) | gateway → auth → billing → card-vault metadata → **risk engine `/v2/assess`** → simulator (network + issuer → card-vault detokenize) → webhook back to billing |
| 4 | `POST /api/payments/{id}/capture` | gateway → auth → billing → simulator |

`checkout_duration` = steps 2–4, the time the patient waits. Step 1 is reported separately (`grpc_account_duration`).

Design choices:
- **Open model** (`constant-arrival-rate` / `ramping-arrival-rate`): k6 starts checkouts at a fixed rate even when
  earlier ones haven't finished. A closed model (N users in a loop) slows down along with the system and hides
  queueing (coordinated omission). If k6 can't keep the rate, it reports `dropped_iterations`.
- **A new patient and a new card every iteration.** Reusing a few would trigger the risk engine's velocity rules
  (4th payment on a card in 1 h → review) and billing's per-card limit, so we'd be measuring those rules.
- **Rate limits lifted for the load-test user** (one k6 login drives everything). The gateway's per-user limits are
  set to 100,000/s. Billing's per-card limit stays on; it never triggers because every card is new.
- **Tracing off** (no OTel agent; the risk engine's span export is disabled). Prometheus scrapes every 15 s as usual.
- **Fresh, separate stack**: compose project `pm-loadtest` with its own empty databases. Dev data is untouched.

## Test machine (all on one laptop: k6 competes with the system for CPU)

| | |
|---|---|
| CPU | Intel i5-13500H, 16 logical CPUs |
| RAM | 14 GB |
| OS | Linux 7.0.0-14, Docker 29.1.3 |
| JVMs | JDK 21, each service `-Xms512m -Xmx1g`, default Hikari pool (10) |
| Risk engine | 1 container, 1 uvicorn worker (as shipped) |

## How to run

```bash
cd ~/Desktop/Projects/patient-management-microservices
sh load-test/start-stack.sh                         # compose (pm-loadtest) + 5 Java services, ~2 min
k6 run -e PROFILE=smoke load-test/checkout.js       # 10 checkouts, all must be CAPTURED

# a measured run: server snapshot before, k6, server diff after
python3 load-test/server_stats.py snapshot load-test/run/before.json
k6 run -e PROFILE=step --summary-export=load-test/results/step-before.json load-test/checkout.js
python3 load-test/server_stats.py diff load-test/run/before.json | tee load-test/results/step-before-server.txt
# optional, in a 2nd terminal during the run: what billing's DB sessions wait on
sh load-test/pg-waits.sh

sh load-test/stop-stack.sh --wipe                   # stop + delete the pm-loadtest volumes
```

Profiles: `smoke` (10 checkouts, 1 VU); `step` (5 → 10 → 15 → 20 → 30 → 40 checkouts/s, 1 min each);
`rate` (`-e RATE=20 -e DURATION=2m`, for before/after).

Thresholds, the targets we report against: errors < 1%, checkout p95 < 2 s, authorize and capture p95 < 1 s,
tokenize p95 < 500 ms, no dropped iterations.

## Found while building the test (before any measured run)

The first smoke runs (2026-10-31) sent ~1% of payments to billing's **fallback** rules: the risk engine answered 422
("looks like a card number") for values that were not card numbers. Both bugs were in the engine's PAN guard
(razorpay-risk-agent `agent/pci.py`):

1. **Device id `dev-<uuid>`**: the UUID rule only matched a UUID not preceded by `-`, so digits inside it
   (`6047-1682-4885-9`) passed Luhn. Real apps send ids like `android-<uuid>`.
2. **Card fingerprint**: card-vault's HMAC-SHA256 hex contains a Luhn-valid 13–19 digit run in **0.44%** of cards
   (measured on 200,000 random digests). A fingerprint never changes, so every payment on such a card always skipped
   the risk engine. Now a whole 64-character lowercase hex token is never read as a card number.
3. Found while testing fix 2, a gap that already existed: **a card number after a short number** (`order 12 4111…`) or
   **two card numbers in a row** were never found, so `mask()` left them in logs and the audit trail. Now every window of
   whole digit groups is checked, longest first.

After the fixes: 601 checkouts at 10/s, 0 answered 422.

## Results

### Before: step test, 5 → 40 checkouts/s (run by you, 2026-10-31, `results/step-before*.{json,txt}`)

What k6 saw over the whole run (the profile ramps 5 → 40/s, so these numbers mix all rates):

| Checkouts | Errors | Dropped | Checkout p50 / p95 / p99 | Authorize p95 / p99 | Capture p95 | Tokenize p95 | New account (gRPC) p95 |
|---|---|---|---|---|---|---|---|
| 8,324 (all CAPTURED) | 0% | 0 | 41 / 121 / 342 ms | 91 / 321 ms | 23 ms | 9 ms | 7 ms |

**k6 looked green, but two thirds of the payments were never risk-checked.** From billing's metrics:

| Risk check | Payments |
|---|---|
| answered by the engine | 2,743 (33%) |
| engine too slow (> 300 ms budget) → fallback rules | 108 |
| **circuit breaker open → fallback rules, engine not asked** | **5,473 (66%)** |

From Prometheus, minute by minute: up to **15/s** the engine answered every check (p99 ≈ 0.1–0.2 s). At **20/s**
(20:03) the engine's p99 went to 0.5–1.0 s, checks timed out, billing's breaker opened (50% of 20 calls failed),
and from then on almost every payment was decided by billing's fallback rules. Authorize p99 = 321 ms is the 300 ms
risk budget being spent. Everything else had headroom: billing's server-side authorize took 39 ms on average,
capture 12 ms, DB pool wait 0.00 ms (109k acquires, max 10 connections), outbox empty at the end, card-vault ≤ 3.4 ms,
simulator ≤ 5.6 ms, auth `/validate` 0.6 ms.

Good: overload made the system degrade safely (no errors, payments kept flowing). Bad: the risk check is the part
that went away, and nothing on the client side shows it. That's why this file reads billing's fallback counter and
not only k6's latencies (dashboard panel "risk fallback rate", Day 20b).

### Bottleneck: the risk engine's CPU time per decision (one worker)

Engine alone, `POST /v2/assess` without billing in front (`razorpay-risk-agent/load-test/assess.js`; run by Claude
while diagnosing, same machine, 30 s per rate):

| Rate | Mean | p99 | Dropped |
|---|---|---|---|
| 10/s | 41 ms | 76 ms | 0 |
| 20/s | 1.52 s | 2.77 s | 3 |
| 30/s | 6.02 s | 10.67 s | 152 |

At 16.7 decisions/s the engine used 0.75 of a core, about 45 ms of CPU per decision. It runs ONE uvicorn worker
(Python: one core), so it queues from ~20/s. Profile of 300 decisions (cProfile + timing each part):

| Part of one decision | ms |
|---|---|
| RandomForest, 300 trees: score + reason-code variants | 17.4 (the forest alone: 9.8, ~33 µs of scikit-learn per-tree overhead for one row) |
| `feature_snapshot`: pandas features again | 3.9 |
| shadow model: pandas features a third time + LogisticRegression | 5.9 |

### Fix (razorpay-risk-agent `model/scorer.py`, `risk_v2/decision.py`)

1. **Forest inference without scikit-learn's per-tree overhead**: call each tree's compiled `tree_.predict` and
   average, the same arithmetic as `predict_proba`. Max difference vs scikit-learn over all 15,000 training rows:
   1e-15. On load the model checks itself on random inputs: if the fast path ever disagrees (e.g. a scikit-learn
   upgrade changes the internals), it switches itself off and scikit-learn is used. `MODEL_FAST_FOREST=false` turns it off.
2. **Features engineered once per decision**: the snapshot and the shadow model reuse the frame of the
   contribution batch (feature engineering is row by row, so this is exact).

In-process, 300 decisions: **38.6 ms → 23 ms per decision (−40%)**: about 32 ms with only fix 2, about 23 ms with both.
Engine alone after the fix (run by Claude): **20/s p99 65 ms** (was 2.77 s); 30/s p99 552 ms (still over the budget).

Next lever (not done): more engine processes (uvicorn workers or replicas behind a load balancer). It needs
Prometheus multiprocess mode, one Kafka consumer per process (the consumer group handles that), and SQLite → a real
DB for the feedback store.

### After: same step test on a fresh stack (run by you, 2026-10-31, `results/step-after*.{json,txt}`)

| | Before | After |
|---|---|---|
| Checkouts / errors / dropped | 8,324 / 0% / 0 | 8,324 / 0% / 0 |
| Checkout p50 / p95 / p99 | 41 / 121 / 342 ms | 53 / 93 / 271 ms |
| Authorize p95 / p99 | 91 / 321 ms | 67 / 251 ms |
| Risk checks answered by the engine | 2,743 (33%) | **5,954 (72%)** |
| Breaker-open fallbacks / timeouts | 5,473 / 108 | 2,327 / 43 |
| **Highest step with the engine answering every check** | **15/s** (breaks at 20/s) | **30/s** (breaks at 40/s) |
| Engine p99 at 15/s / 20/s / 30/s (Prometheus, 1 min) | 0.2 / 0.5–1.0 s / (breaker open) | 0.05 / 0.07–0.09 / 0.19–0.24 s |
| Engine CPU per decision (CPU ÷ decisions/s) | ~45 ms (0.75 cores at 16.7/s) | ~23 ms (0.69 cores at 30/s) |
| Engine mean as billing saw it | 48.5 ms | 26.5 ms |

The median checkout went up (41 → 53 ms) because now most payments wait for a real risk decision: before, two thirds
skipped it, and the fallback rules take ~6 ms. p95 and p99 went down.

**Capacity of the whole checkout path on this laptop is now ~30 checkouts/s with every payment risk-checked**
(before: ~15/s), still limited by the one risk-engine worker. Next: more engine processes (see above).

## Numbers to quote (all from the tables above)
- Through the whole path (gateway, auth, card-vault, billing, risk engine, simulator, webhooks): 8,324 checkouts,
  0 errors, p95 93 ms, on one laptop.
- The load test found that overload silently skipped the risk check for 66% of payments, though k6 showed no errors.
  The cause was the risk engine's CPU per decision. Fixed: 45 → 23 ms, and the rate at which every payment is still
  risk-checked doubled (15 → 30 checkouts/s).
- Found 3 card-number-guard bugs on the way; one sent 0.44% of cards to fallback rules on every payment.
