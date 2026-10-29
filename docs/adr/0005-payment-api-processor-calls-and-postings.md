# ADR 0005 — Payment API: processor calls outside DB transactions; ledger posts only when money moves

- Status: Accepted
- Date: 2026-10-29

## Context
Day 4a adds `POST /payments` (authorize), `/capture` and `/void`. Each one calls an external processor (today a fake,
Day 7 a simulated acquirer over the network) and updates our DB. We must decide (1) how DB transactions relate to
the external call, (2) which steps create ledger entries and on which accounts, (3) the HTTP contract.

## Decision
1. **Three steps per operation**: short transaction to read + validate (`checkCapture`/`checkVoid` throw exactly
   what the real move would) → processor call with **no** transaction open → short transaction to apply the result
   (`@Version` catches concurrent changes). Authorize saves the payment as CREATED *before* calling the processor.
2. **Ledger posts only when money moves.** Authorize and void: no entry (a hold is not money). Capture: one
   compound entry, in the same transaction as the status change:

   | Line | Account | Meaning |
   |---|---|---|
   | DR | PATIENT_RECEIVABLE | patient is charged for the service |
   | CR | HOSPITAL_REVENUE | revenue earned |
   | DR | PROCESSOR_RECEIVABLE (new, ASSET) | the acquirer now owes us the money until settlement |
   | CR | PATIENT_RECEIVABLE | patient paid it by card |

   Net: patient receivable 0, processor receivable +amount, revenue +amount. Settlement (Day 7/8) will clear
   PROCESSOR_RECEIVABLE against cash.
3. **HTTP**: amounts are decimal **strings** + ISO currency. `201 Created` + `Location` for a new payment, **even when
   declined** (status FAILED + `failureReason`). 400 invalid input, 404 unknown payment, 409 wrong state / concurrent
   change, 422 unknown billing account, 502 processor refused. All `application/problem+json`.

## Alternatives considered
| Option | Why not |
|---|---|
| One `@Transactional` around read + processor call + write | Holds a DB connection (and, after the UPDATE, a row lock) for the whole network call. Under load the pool empties and requests queue behind a slow acquirer. A rollback also can't undo the processor's side. |
| Post a ledger entry at authorize (e.g. "authorizations pending" account) | Valid in some ledgers, but adds two accounts and reversals on void/expiry for money that never moved. Holds are tracked on the payment row and at the processor. |
| Capture = DR PROCESSOR_RECEIVABLE / CR REVENUE only | Simpler, but the patient receivable would never be used, and Day 9+ (invoices, insurer splits) needs the charge recorded against the patient. |
| `402 Payment Required` for a declined card (Stripe style) | Also reasonable. 201 keeps "the resource exists, read its status" uniform; the FAILED payment has an id clients can show and audit. |

## Consequences
- + No DB resources held during network calls; a 409 is returned before touching the card.
- + Ledger and payment status change atomically; trial balance stays zero (tested).
- − **Dual-write gap**: if the processor succeeds and step 3 fails (crash, version conflict), the processor and our DB
  disagree. Closed by idempotency keys + retries (Day 5) and reconciliation against the acquirer (Day 8).
- − Two concurrent captures can both pass step 1; the fake processor rejects the second, which currently surfaces as
  **502** rather than 409. Day 5 idempotency makes the retry case clean.
- − `IllegalArgumentException` is mapped to 400 globally, so a programming error throwing it would look like a client error.
