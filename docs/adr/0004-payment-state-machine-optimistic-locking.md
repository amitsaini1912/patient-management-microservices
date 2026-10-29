# ADR 0004 — Payment state machine as an explicit table + optimistic locking with `@Version`

- Status: Accepted
- Date: 2026-10-29

## Context
A payment moves through CREATED → (PENDING_REVIEW) → AUTHORIZED → CAPTURED → (PARTIALLY_)REFUNDED, or ends in
FAILED / VOIDED / EXPIRED. Wrong moves are expensive: capturing a voided authorization charges a customer whose hold
was released; refunding twice pays out money we don't have. The same payment is touched concurrently by API
requests, the auth-expiry job (Day 4b) and network webhooks (Day 7).

## Decision
- **Transitions in one table**: `PaymentStateMachine` holds a `Map<PaymentStatus, Set<PaymentStatus>>`. Anything not
  in the map is illegal → `IllegalPaymentStateException` → HTTP **409** (`application/problem+json`).
- Status changes only through intent methods on `Payment` (`authorize`, `capture(Money)`, `voidAuthorization`,
  `expire`, `refund(Money)`, `fail(reason)`, `holdForReview`); there is no `setStatus`.
- **Partial capture is not a status**: CAPTURED with `captured_amount < amount`. Refunds are capped by the captured
  amount, not the authorized one. A second partial refund is the self-move PARTIALLY_REFUNDED → PARTIALLY_REFUNDED.
- **Optimistic locking**: `@Version Long version`. Every UPDATE is `… WHERE id = ? AND version = ?`; if another
  transaction got there first, 0 rows match → `ObjectOptimisticLockingFailureException` → **409**, client re-reads.
- **DB CHECKs** make amounts consistent with status (captured ≤ authorized, refunded ≤ captured, captured > 0 ⇔
  CAPTURED/PARTIALLY_REFUNDED/REFUNDED, REFUNDED ⇔ refunded = captured, failure_reason ⇔ FAILED).
  The transition graph itself is **not** duplicated in SQL (see Alternatives).

## Alternatives considered
| Option | Why not |
|---|---|
| `if/else` or `switch` spread across services | Rules get scattered; nobody can see the whole graph; easy to miss a case. |
| Spring Statemachine | Heavy (config, persisters, listeners) for 9 states and 12 moves. A map is fully testable in one test class. |
| Pessimistic lock (`SELECT … FOR UPDATE`) | Holds a row lock across the whole request, including the call to the card network (seconds). Conflicts here are rare, so paying the lock cost on every request is waste. Will be used where a short critical section needs it. |
| No locking (last write wins) | Lost update: a capture and a void both "succeed" and the row says whichever committed last. Proven by temporarily removing `@Version`: the race test then fails. |
| Transition trigger in Postgres (like the ledger) | Second copy of the graph to keep in sync. The ledger is the money's source of truth and gets the DB guard; the payment row is protected by version + CHECKs, and every money move on it will also be a balanced ledger entry (Day 4a). |

## Consequences
- + One place to read and change the lifecycle; an 81-case parameterized test pins every (from, to) pair.
- + No lost updates; no locks held while waiting on external systems.
- − Under real contention one side gets 409 and must retry. Callers (API clients, the expiry job, webhook consumers)
  must handle that: re-read, re-check the state, then decide.
- − The state machine is enforced in Java only; raw SQL could still set an illegal status that happens to satisfy
  the CHECKs (e.g. VOIDED → AUTHORIZED).
