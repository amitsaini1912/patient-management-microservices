# ADR 0017 — Risk check before authorization: 300 ms budget, one retry, circuit breaker, fail open / to review

- Status: Accepted
- Date: 2026-10-31
- Plan name: "ADR-007" (numbers shifted, see PROGRESS). Other side of the contract: razorpay-risk-agent ADR 0001/0003.

## Context
billing-service authorized every card payment it was asked to. The risk engine (razorpay-risk-agent, `POST
/v2/assess`) can say allow / hold for review / block, but nothing called it. Calling it puts a network hop owned by
another team in the middle of every payment: it can be slow, down, or wrong, and payments must not stop.

## Decision
1. **When**: after the card is resolved and the payment is saved as CREATED, **before** anything is sent to the
   card network (pre-authorization). No transaction is open during the call (same rule as the processor call).
   - ALLOW → authorize as before. HOLD → `PENDING_REVIEW` (event `payment.held_for_review`), nothing sent to the
     network. BLOCK → `FAILED` with `risk_declined`, nothing sent.
   - The verdict is kept on the payment (decision, ENGINE/FALLBACK, reason codes, score, model + rules version)
     and in the audit log. The client IP (gateway's `X-Forwarded-For`) and `X-Device-Id` are stored at creation,
     so a retried authorize sends the same facts and gets the engine's stored decision (its ADR 0003).
2. **Facts sent** (risk ADR 0001): payment id as `transaction_id`, amount in minor units + currency, vault token,
   fingerprint, last4, billing account id as `customer_id`, `merchant_id` = `hospital-main`, IP, device, created
   time. No risk features: the engine computes them.
3. **Budget 300 ms for the whole check** (`billing.risk.budget`), JDK HttpClient with a per-call timeout = what is
   left. **One retry**, only after a fast failure (connection refused, 502/503/504) and only with ≥ 50 ms left. A
   timeout is not retried (the budget is spent); 401/409/422 aren't either (the same request fails again).
4. **Circuit breaker** (Resilience4j, used directly): opens when ≥ 50% of the last 20 calls failed (≥ 10 calls);
   while open, payments skip the engine; after 30 s, 3 trial calls decide whether it closes.
5. **Fallback = billing's own rules** (`FallbackRiskRules`), also used when `billing.risk.enabled=false` (default, so
   the repo runs alone):
   - **fail open** below INR 50,000 (book currency, at the locked rate): a hospital can't stop taking payments
     because fraud scoring is down;
   - **fail to review** from INR 50,000, and from INR 10,000 for foreign-currency cards;
   - **never block** without the engine: no evidence to decline on.
6. **Review**: `POST /payments/{id}/review` `{"decision": "APPROVE" | "REJECT", "note"}` — **role ADMIN only**
   (`X-User-Role` from the gateway, 403 otherwise; first RBAC in billing, weakness #8). Approve → authorize now
   (the network may still decline); reject → `FAILED` `risk_review_rejected`. Only `PENDING_REVIEW` can be reviewed
   (409). Reviewer, time and note are kept and audited (`user:<X-User-Id>`). A rejection that wins a race with an
   in-flight approval voids the hold that approval placed.
7. Metrics: `billing.risk.decisions{decision,source,fallback_reason}`, `billing.risk.duration{source}`.

## Fail open vs fail closed
| Option | Fraud while risk is down | Good payments while risk is down | Chosen |
|---|---|---|---|
| Fail closed (decline all) | none | all lost - hospital can't take payments | no |
| Fail open (allow all) | all of it goes through | none lost | below the limit |
| Hold everything for review | none | delayed; review queue floods | no |
| Fail open below a limit, review above it | small amounts only | small: none; big: delayed | **yes** |

## Results (real, 2026-10-31)
- Tests: billing 322 → **367** (`mvnw verify`), also with `-Dsurefire.runOrder=alphabetical` and
  `reversealphabetical`. New: `RiskAssessorTest` 8 (budget, one retry, no retry without time / when useless,
  breaker opens and skips, bug → fallback), `HttpRiskEngineClientTest` 17 (facts + key, every action, each error's
  retryability, timeout, unreachable, unreadable answers), `FallbackRiskRulesTest` 3, `RiskContextTest` 3,
  `RiskCheckIntegrationTest` 10 (real Postgres: allow/hold/block, retry of a held payment, engine 503 → fallback
  after one retry, slow engine, approve, approve + network decline, reject, only held can be reviewed),
  `PaymentControllerTest` +4 (context forwarded, 403 for missing/USER/lower-case admin, reviewer named, bad decision).
- By hand (compose: billing-db, kafka, redis, **risk-engine built from ../razorpay-risk-agent**; billing jar on the
  host with `BILLING_RISK_ENABLED=true`, fake acquirer + vault): INR 1,500 new customer → ALLOW (0.08); INR 45,000
  same new customer → BLOCK (R02_AMOUNT_ANOMALY, 0.825); 5 × INR 900 on one card in a minute → ALLOW then HOLD
  ×4 (R01_VELOCITY); the 6th → 429 from billing's own card limit, before risk. Review: USER → 403, ADMIN approve →
  AUTHORIZED, reject → FAILED, second review → 409; audit rows `user:admin@test.com`.
- Engine hung (`docker pause`): payments 0.33–0.42 s end to end, all FALLBACK (normal → AUTHORIZED, INR 60,000 →
  PENDING_REVIEW); after 8 timeouts the breaker opened → 24–46 ms per payment. Unpaused: OPEN → HALF_OPEN →
  CLOSED after 30 s, engine decisions again (130–190 ms per payment end to end).
- The risk step measured at most ~365 ms on a cold client in tests (300 ms budget + connection setup).

## Consequences
- Every payment pays up to 300 ms more (typically ~20–60 ms when the engine is healthy, Day 16 numbers).
- A payment can wait in `PENDING_REVIEW` forever: no expiry / SLA job yet, no review queue endpoint (the index
  `payment_pending_review_idx` is there for it).
- RBAC trusts `X-User-Role`; billing is only safe behind the gateway (dev compose exposes 4001 directly — same
  trust-boundary note as Day 12).
- Payments decided by the fallback are not seen by the engine: after an outage its velocity counts miss them.
- `X-Device-Id` is optional; without it every payment's device is `unknown`, so the engine's new-device signal is weak.
- The engine refuses (422) a field that looks like a card number; a UUID with a long Luhn-valid digit run would be
  refused → fallback for that payment (rare; logged as `http_422`).
- Chargebacks and capture/review outcomes back to the engine: Day 19b.
