# ADR 0006 — Refunds as records with mirror postings; authorization expiry via `FOR UPDATE SKIP LOCKED`

- Status: Accepted
- Date: 2026-10-29

## Context
Day 4b adds refunds (full and several partial) and a job that expires authorizations never captured. Refunds send real
money back, so a refund the processor made must never be lost from our books. The expiry job will run on every
billing-service instance, so two instances must not expire the same payment twice or block each other.

## Decision
1. **Refund = its own row** (`refund` table: amount, processor reference, reason). `payment.refunded_amount_minor`
   stays the running total; `POST /payments/{id}/refunds` → 201 + Location; `GET` lists them. No body = refund what's left.
2. **Refund postings mirror capture**: DR REFUNDS / CR patient receivable (charge credited back) and
   DR patient receivable / CR processor receivable (money back to the card). REFUNDS is contra-revenue, kept apart
   from HOSPITAL_REVENUE so refunds stay visible.
3. **Retry the DB step on `@Version` conflict for refunds** (up to 10 attempts). After the processor has refunded,
   a 409 would drop a real refund. Re-reading and re-applying is safe: refunds are additive and the processor already
   enforced the total. (Capture/void are not retried: the processor rejects a duplicate, so there's nothing to record.)
4. **State check before amount check** in `checkRefund`, so "refund after full refund" is 409 even with no amount.
5. **Expiry job**: `@Scheduled` every 5 min. One short transaction per batch:
   `SELECT … WHERE status='AUTHORIZED' AND authorized_at < now-TTL ORDER BY authorized_at LIMIT n FOR UPDATE SKIP LOCKED`,
   expire them, commit. Then, outside the transaction, release holds at the processor (best effort; issuers drop old
   holds anyway). TTL `billing.payments.authorization-ttl=7d`. New `authorized_at` column + partial index
   `WHERE status = 'AUTHORIZED'`. Time comes from an injected `Clock`.

## Alternatives considered
| Option | Why not |
|---|---|
| Refund only as a counter on `payment` | No history of individual refunds, no per-refund processor id to reconcile. |
| Debit HOSPITAL_REVENUE directly on refund | Works, but hides how much was refunded inside net revenue. |
| Return 409 when the refund's DB write loses the race | The customer got the money back; our books wouldn't show it. |
| ShedLock / Quartz cluster lock | Extra library + table; also makes ONE instance do all the work. SKIP LOCKED lets instances share it with no new dependency. |
| Plain `FOR UPDATE` | Correct, but instance B waits for A's whole batch. Proven: the test times out without SKIP LOCKED. |
| Rely on `@Version` alone | Two instances load the same batch; one fails at commit and rolls back its entire batch. |
| Call the processor inside the expiry transaction | Holds row locks during network calls (see ADR 0005). |

## Consequences
- + Refunds are auditable one by one; ledger, refund rows, payment total and processor agree (tested with 8 concurrent refunds).
- + Expiry scales out with instances; no lock library.
- − Two concurrent refunds that together exceed the refundable amount both pass our pre-check; the processor rejects
  the second → 502 (should be 400). Acceptable until idempotency/reconciliation.
- − A capture already at the processor when the job expires the payment hits the dual-write gap (ADR 0005).
- − `Payment.authorize` still stamps `Instant.now()`, not the injected `Clock`; tests move the job's clock instead.
