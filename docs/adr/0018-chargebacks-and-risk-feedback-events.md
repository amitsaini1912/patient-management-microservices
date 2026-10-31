# ADR 0018 — Chargebacks in the ledger, and feedback events for the risk engine

- Status: Accepted
- Date: 2026-10-31
- Builds on: ADR 0008 (outbox), 0011 (reconciliation), 0012 (multi-currency), 0017 (risk check).
  Other side: razorpay-risk-agent ADR 0005 (labels) and 0006 (payment outcomes), schemas in its `docs/contracts/`.

## Context
- billing had no chargebacks at all: a disputed payment kept looking fully earned, and the risk engine never learned
  which of its decisions were wrong.
- The engine's precision/recall were lower bounds because it didn't know which payments were captured (only those
  can charge back) or what a person decided about the ones it held.

## Decision
1. **Recording a chargeback**: `POST /payments/{id}/chargebacks` `{amount?, currency?, reason, caseReference?}`,
   ADMIN only (ops, from the acquirer's dispute notice), Idempotency-Key → chargeback id (like refunds). No processor
   call: the network already took the money. `reason` ∈ fraud, product_not_received, duplicate,
   credit_not_processed, other. `GET /payments/{id}/chargebacks`.
2. **Only money we still hold**: payment CAPTURED or PARTIALLY_REFUNDED, amount ≤ captured − refunded − charged back
   (409 / 400). `payment.charged_back_amount_minor` is the running total; refundable shrinks by it; DB CHECK
   `refunded + charged_back <= captured`. The status doesn't change; REFUNDED still means "we refunded all of it".
3. **Ledger**: a loss, not a refund — `DR CHARGEBACKS (expense) / CR PROCESSOR_RECEIVABLE`. Revenue and the patient's
   receivable stay as they are (trying to collect again is a human decision). Foreign currency: the loss at TODAY's
   rate via FX clearing (`INR DR chargebacks / CR FX clearing`, `USD DR FX clearing / CR processor receivable`).
   Source type CHARGEBACK; **settlement reconciliation only matches CAPTURE/REFUND** (chargebacks are on the
   acquirer's dispute report, not its settlement report).
4. **Events for the risk engine**, JSON through the same transactional outbox, key = payment id = the engine's
   `transaction_id`, times at millisecond precision (UTC):
   - `chargeback.created` v1 — event_id, transaction_id, amount_minor, currency, reason, created_at, schema_version;
   - `payment.outcome` v1 — `captured` (on every capture, our call or the acquirer's webhook), `review_approved`,
     `review_rejected`; amount_minor, currency, occurred_at.
   Topics created at startup like `payment.events`. Audit: `payment.charged_back` with the chargeback details.
5. **Risk facts timestamp at millisecond precision** (fix): Java's Instant has nanoseconds, Postgres keeps
   microseconds — a retried authorize (payment re-read from the DB) sent a different `timestamp` string than the
   first attempt, which the engine answers with 409 (same id, different facts) → fallback.

## Results (real, 2026-10-31)
- billing `mvnw verify`: 367 → **379** (default, alphabetical, reverse order). New: `ChargebackIntegrationTest` 8
  (ledger loss, contract shape of chargeback.created, idempotent key, chargebacks + refunds share what's left, only
  held money, USD at today's rate, captured outcome, review outcomes), `ChargebackReconciliationTest` 1,
  controller +3 (403, 201 + Location, reasons validated), risk-client timestamp precision. Two existing tests
  adjusted: outbox event lists now filter `payment.events`; a DB-constraint test accepts either of the two CHECKs
  that now reject "refunded > captured".
- By hand (compose with the risk engine, billing jar with `BILLING_RISK_ENABLED=true`): INR 1,800 ALLOW → capture →
  chargeback (fraud) → risk `/feedback/{id}` label 1, outcome `captured`; the same customer's next payment was HELD
  with `R05_CHARGEBACK_HISTORY` (the engine's past_chargebacks came from billing's chargeback) → ADMIN approve →
  capture → risk outcomes `captured` 2, `review_approved` 1.
- Found by hand: the engine had subscribed before billing created `payment.outcome` (and before billing grew
  `chargeback.created` from 1 to 3 partitions): librdkafka's 5-minute metadata refresh left billing's events unread.
  Fixed in the risk engine (10 s refresh, its ADR 0006).

## Consequences
- Re-presentment (the hospital disputes the chargeback and wins the money back) is not modelled.
- Chargebacks are entered by hand: the acquirer simulator sends no dispute webhook.
- Chargeback fees are not booked.
- `payment.outcome` is sent for every payment, also the ones the engine never saw (billing's fallback decided):
  the engine counts them as unmatched.
- analytic-service doesn't see chargebacks (no `payment.events` message for them yet).
