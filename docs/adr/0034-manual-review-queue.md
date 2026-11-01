# ADR 0034 — The manual-review queue: list, metric, alert

- Status: Accepted
- Date: 2026-11-01
- Builds on ADR 0017 (risk HOLD -> PENDING_REVIEW, ADMIN decides with POST /payments/{id}/review).

## Context
A payment the risk check holds waits for a person - the patient can't pay until then (nothing is sent to the card
network and nothing is reserved). But there was no way to SEE the held payments: a reviewer needed the payment id from
somewhere else, and nobody noticed a growing queue. The partial index for it (V14) existed and was never used.

## Decision
- `GET /payments/reviews?limit=50` (gateway: `/api/payments/reviews`), ADMIN only: held payments oldest first, as the
  normal payment response - amount, card, and the risk part (decision, source, reason codes, score) a reviewer needs.
  `limit` 1-200.
- Gauges `billing_payments_pending_review` and `billing_payments_pending_review_oldest_age_seconds` (SQL per scrape on
  the partial index, NaN when the DB is down - like the outbox gauges).
- Alert `ReviewQueueWaiting` (ADR 0032): the oldest held payment has waited > 1 h for 5 min (promtool-tested).

## Alternatives
- Auto-reject (or auto-approve) after a deadline: a business decision with real cost either way (lost payment vs.
  unreviewed risk); not taken. The alert makes a person decide; an SLA can be added on top.

## Consequences
- No paging (no Alertmanager yet, ADR 0032), no assignment of reviews to people, no "claimed by" lock: two admins can
  open the same payment; the review itself is safe (the first decision wins, the second gets 409 - ADR 0017).
- Tests: `ReviewQueueIntegrationTest` (2: held payments listed oldest first with reason codes, normal ones not, gone
  once decided, gauges set; ADMIN only), promtool test for the alert.
