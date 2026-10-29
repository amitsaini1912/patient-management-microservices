# ADR 0003 — Double-entry, append-only ledger enforced in code AND in Postgres

- Status: Accepted
- Date: 2026-10-29

## Context
billing-service will charge patients, capture payments, refund and convert currencies. We need to answer
"what does patient X owe?" and "how much revenue did we earn?" and be able to *prove* the answer. A single
`balance` column that we `UPDATE` can't do that: a bug or a lost update changes the number and leaves no trace.

## Decision
- **Double-entry**: every money movement is a `journal_entry` with ≥ 2 `entry_line`s. Each line has a positive
  `amount_minor` and a `direction` (DEBIT/CREDIT). Per currency, debits = credits.
- **Accounts** (`ledger_account`): `type` (ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE) gives the normal balance side;
  `purpose` says what it is for. One `PATIENT_RECEIVABLE` per billing account (created in the same transaction
  as the billing account); system accounts per currency: INSURER_RECEIVABLE, HOSPITAL_REVENUE, REFUNDS
  (contra-revenue, debit-normal), FX_GAIN_LOSS, SUSPENSE. INR seeded in V2.
- **Rules enforced twice**:
  1. In code: `JournalEntry.post()` refuses < 2 lines, non-positive amounts, account/line currency mismatch, and
     unbalanced currencies.
  2. In Postgres: `CONSTRAINT TRIGGER … DEFERRABLE INITIALLY DEFERRED` re-checks line count and balance at COMMIT;
     `CHECK (amount_minor > 0)`; composite FK `(ledger_account_id, currency) → ledger_account (id, currency)`.
- **Append-only**: `BEFORE UPDATE/DELETE/TRUNCATE` triggers raise. Corrections are reversal entries
  (`reversal_of`, `UNIQUE` → an entry is reversed at most once). JPA entities are `@Immutable`.
- **Balances are derived** from lines (`sum(debits) − sum(credits)`, sign flipped for credit-normal accounts).
  The optional `account_balance` cache from the plan is **not built yet** (see Consequences).

## Alternatives considered
| Option | Why not |
|---|---|
| A `balance` column updated in place | No history, lost updates under concurrency, can't be audited. |
| Single-entry (one signed row per movement) | Simpler, but nothing forces the "other side" to exist; money can appear from nowhere. |
| Rules in Java only | Raw SQL, a second service or a bad migration can bypass them. The DB is the last line of defence. |
| Rules in DB only | Errors arrive late (at COMMIT) as SQL errors; code checks give clear messages early. |
| Immediate (non-deferred) balance trigger | Fails on the first line insert, because an entry is only balanced after all its lines are in. |
| A ledger product (TigerBeetle, Formance, Modern Treasury) | Right answer at scale; here the point is to show we understand the mechanics. |

## Consequences
- + Every rupee is traceable to an entry; history can't be rewritten, only corrected visibly.
- + `sum` over all lines per currency is always zero — a cheap global health check for reconciliation (Day 8).
- − Balance reads scan the account's lines (indexed by `ledger_account_id`). Fine now. A cached `account_balance`
  row updated in the same transaction is the next step when reads get hot, at the cost of row-lock contention
  on popular accounts (e.g. HOSPITAL_REVENUE).
- − Tests can't clean up ledger tables (append-only); each test uses fresh accounts.
- − Deferred check errors surface at COMMIT, so a caller's outer transaction fails late, not at `save()`.
