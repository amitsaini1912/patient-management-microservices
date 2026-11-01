# ADR 0024 — Resolving payments whose authorization outcome is unknown

- Status: Accepted
- Date: 2026-11-01
- Follows: ADR 0023 (acquirer no-answer = 503 "outcome unknown, retry with the same key"), `docs/CHAOS.md` finding 3.

## Context
billing saves a payment CREATED, then calls the acquirer. If that call times out, or billing dies before recording the
answer, only a client retry with the same Idempotency-Key (ADR 0007) or the acquirer's webhook (ADR 0010) settles it.
In the Day 23 chaos run (simulator frozen 60 s under k6) neither was enough: 121 payments stayed CREATED for ever, and
any of them authorized at the acquirer held money on a card with nobody ever capturing it.

## Decision
A scheduled job in billing (`StaleAuthorizationResolver`, every minute) takes payments still **CREATED 15 minutes**
after they were saved (oldest first, 100 per run) and sends the acquirer a **status inquiry** - never the
authorization again:

| Acquirer says | billing records |
|---|---|
| nothing received | FAILED `not_received_by_acquirer` |
| declined | FAILED with the acquirer's reason, response code, retryable flag |
| authorized | AUTHORIZED, then **VOIDED** at once (hold released today, not in 7 days) |
| can't say (timeout, error, already voided/captured there) | left CREATED, asked again next run; the run stops after the first failed inquiry |

- Inquiry = `GET /v1/authorizations?merchant_reference={paymentId}` on the simulator (new, read-only), like a real
  acquirer's "find by order id". `PaymentProcessor.findAuthorization(paymentId)`; the fake processor answers it too.
- Recording locks the row and re-checks CREATED: if a client retry or a webhook settled it meanwhile, nothing is done.
  The void goes through `PaymentService.voidAuthorization` (one void key per payment), so the acquirer voids once even
  if two billing instances resolve the same payment. No SKIP LOCKED claim: rows are read without a lock because the
  acquirer is called between reading and recording.
- Why void rather than keep the authorization: nobody was told it succeeded (the client got 503 and gave up), so
  nobody will capture it. A late retry with the old key gets the VOIDED / FAILED payment and must start a new one.
- 15 minutes is far above any live request (risk 300 ms + vault 2 s + acquirer 5 s + client retries), so the job never
  races a request that is still working. Partial index `payment_created_stale_idx` (V18) keeps the query small.
- Counter `billing_payments_stale_resolved_total{outcome}` (not_received, declined, voided, authorized_not_voided,
  already_settled, unresolved).

## Consequences
- No payment stays CREATED longer than ~16 minutes while the acquirer answers inquiries.
- Accepted gap: a request delayed in the network for more than 15 minutes that reaches the acquirer AFTER we recorded
  "not received" is authorized there; its webhook is ignored as stale and the hold lapses after ~7 days.
- Not covered: payments billing DID record as AUTHORIZED whose client then never captured (the 59 "orphans" of the
  chaos run). billing can't tell them from a merchant who captures on day 6; the 7-day expiry job stays their answer.
- Tests: `StaleAuthorizationResolverIntegrationTest` (8: each outcome, young rows untouched, late retry gets the
  settled payment with one acquirer authorization, retry during the inquiry wins, acquirer down -> one inquiry and
  nothing changed, PENDING_REVIEW untouched), adapter + simulator inquiry tests.
