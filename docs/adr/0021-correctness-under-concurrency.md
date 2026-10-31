# ADR 0021 — Correctness under concurrency: storms, a row lock for recording, property tests

- Status: Accepted
- Date: 2026-10-31
- Builds on: ADR 0004 (optimistic locking), 0006 (refunds), 0007 (idempotency). Other side: razorpay-risk-agent
  ADR 0009 (its property tests).

## Context
Earlier tests showed single behaviours (16 duplicates of one request, 8 concurrent refunds). Nothing showed that money
stays exact when hundreds of requests on one account and one payment overlap, retried by clients the way real ones
retry, or that the domain rules hold for inputs nobody thought of.

## Decision
1. **Storm tests** (`CorrectnessIntegrationTest`, real Tomcat + Postgres, 48 client threads, fake acquirer with
   10–15 ms latency). Clients re-send while the answer is "in progress" (409 + Retry-After) or 5xx.
   - one account: 30 payments × 4 duplicate senders, captures × 3, per payment refunds of 30% and 25% (× 3 senders)
     racing a 60% refund (≈ 370 final requests);
   - one payment: 120 concurrent ₹100 refunds against ₹10,000 captured.
   After each, everything must agree: one payment per key; every acquirer operation executed **once** (authorize,
   capture, each refund); refund rows = running total = the acquirer's refunded; captured = the acquirer's captured;
   one outbox event per change; the patient receivable is 0; revenue / refunds / processor receivable moved by exactly
   the totals; **every journal entry balances per currency, the whole ledger sums to 0**, no entry without lines; the
   audit hash chain verifies; no 5xx.
2. **Recording locks the payment row** (`PaymentRepository.findByIdForUpdate`, `SELECT … FOR UPDATE`) in every step
   that records what the acquirer already did (authorize, review, capture, void, refund, chargeback). Concurrent
   requests on one payment record one after another. `@Version` + the bounded retry stay for the unlocked path
   (acquirer webhooks). Lock order unchanged: payment row, then the audit chain head.
3. **A refused amount is 409, not 502**: `PaymentProcessorException.refused` when the acquirer refuses the AMOUNT
   (more than is left: simulator 422 `amount_invalid`, the fake's "exceeds"). State refusals ("already voided",
   simulator 409 `invalid_state`) stay 502 — then our records and the acquirer's disagree and re-reading won't help.
4. **Property tests (jqwik 1.9.2)**:
   - `MoneyPropertiesTest`: + is commutative / associative / invertible, overflow throws, allocation never loses or
     creates a minor unit and every part is < 1 unit from its exact share, parse ↔ toDecimal round-trips (0/2/3-
     decimal currencies), too many decimals is an error, conversion is at most half a unit off and monotonic;
   - `PaymentStateMachinePropertiesTest`: 2,000 random sequences (≤ 25 operations: authorize, fail, hold, approve,
     reject, capture, void, expire, refund, chargeback; amounts random, "all" or "half" of what is left) — a refused
     operation changes nothing, an accepted one follows PaymentStateMachine, and after every step the Java object
     satisfies the database's CHECK constraints (V3, V5, V14, V15); a FAILED payment accepts nothing.
5. **`Money.allocate` uses largest remainders** (see Results).

## Results (real, 2026-10-31)
- billing `mvnw verify`: 386 → **398** (default, alphabetical, reverse order). Storm test also 3× more in a row.
- **Found: refunds done at the acquirer but not recorded.** First storm run: 120 concurrent refunds on one payment →
  only 81 recorded; 19 × "gave up recording a completed processor operation after 10 attempts" (optimistic-lock
  retries exhausted). The money had gone back to the card; the 19 clients got 409; only a client retrying with the
  same key (or reconciliation) would have healed it. With the row lock: 100 recorded, 20 refused, 0 version errors —
  in every run.
- **Found: losers got 502.** The 20 refused refunds now get 409 (each run: `{201=100, 409=20}`); account storm final
  statuses e.g. `{201=246, 200=75, 409=47, 400=2}`, never 5xx.
- **Found: unfair allocation.** jqwik shrank a counterexample to ₹0.08 split 5:1:34 → [0.02, 0.00, 0.06] (exact
  shares 0.01, 0.002, 0.068): the leftover paisa went to the first part, not the largest remainder. Fixed (only
  tests used `allocate`; MoneyTest's examples unchanged) → [0.01, 0.00, 0.07].
- Break checks: counting refundable from `captured` (ignoring chargebacks) → state-machine property fails; REFUNDED
  when "nothing left" instead of "all refunded" → fails (only after drawing "all"/"half" amounts on purpose — random
  amounts never hit the exact remainder); refund recording without the lock → storm test fails.
- First version of the refusal change also made "already VOIDED" a 409; `IdempotencyIntegrationTest` caught it →
  narrowed to amount refusals.

## Consequences
- A hot payment serializes its recording step (milliseconds per refund); throughput on ONE payment is bounded by it.
  Payments don't block each other.
- The storm test adds ~20 s to the suite.
- The fake acquirer, not the simulator, is in the storm: the simulator's own concurrency is not covered here
  (Day 23 chaos runs against it).
