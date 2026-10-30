# ADR 0011 — Daily reconciliation of the ledger against the acquirer's settlement report

- Status: Accepted
- Date: 2026-10-30

## Context
Idempotency (ADR 0007), the outbox (0008) and healing webhooks (0010) make disagreements between us and the acquirer
rare, but not impossible: a client that never retries, a lost webhook, a refund the webhook only records, or a
mistake on the acquirer's side. Finance needs an independent daily check: did every capture and refund in our
ledger happen at the acquirer, for the same amount and currency, and the other way round?

## Decision
1. **Simulator**: `GET /v1/settlements/{date}` returns every capture (`cap_…`) and refund (`re_…`) booked that UTC day,
   JSON: `id, type, authorization_id, merchant_reference, amount_minor, currency, occurred_at`. Test knob:
   `PATCH /admin/settlements/{id}` changes a row's amount/currency (an acquirer-side error).
2. **Our side comes from the ledger**, not from payment rows: `journal_entry` gets `source_type` (CAPTURE/REFUND) +
   `source_id` (payment id / refund id); the amount is the line on the PROCESSOR_RECEIVABLE account, i.e. what the
   ledger says the acquirer owes us. A unique index `(source_type, source_id)` makes "posted twice" impossible.
   Entries posted before V9 aren't backfilled (it would mean disabling the append-only trigger; dev data only).
3. **Matching keys**: capture ↔ payment id = the acquirer's `merchant_reference`; refund ↔ the acquirer's refund id =
   our `refund.processor_reference`. Amounts summed per key on each side.
4. **Classes**: `MISSING_IN_LEDGER`, `MISSING_IN_SETTLEMENT`, `CURRENCY_MISMATCH` (checked before amount),
   `AMOUNT_MISMATCH`. Matched transactions are only counted; differences are stored as `recon_item` rows of a
   `recon_run`.
5. **Days are UTC** `[00:00Z, 24:00Z)` on both sides. To survive cut-off skew (we post at 23:59:59.9, the acquirer
   books at 00:00:00.1), the counterpart is searched in a **3-day window (D−1..D+1)**: an item dated D on either side
   is matched if its partner exists anywhere in the window.
6. **When**: `@Scheduled(cron = "0 0 2 * * *", zone = "UTC")` reconciles **yesterday**: a day's report is only complete
   after the day ends (real acquirers publish hours later and pay out T+1). Manual: `POST /reconciliation/runs?date=`
   (201; 502 if the report can't be fetched, stored as a FAILED run; future dates 400).
   `GET /reconciliation/runs/{date}` → the latest run. Re-runs are allowed; the latest counts.
7. **Status before ledger**: `PaymentService` now flushes the payment's version-checked UPDATE before posting the
   journal entry. Found on Day 8: with the new unique index, Hibernate's insert-before-update flush order made
   racing captures hit the index (500) instead of `@Version` (retried → 200).

## Alternatives considered
| Option | Why not |
|---|---|
| Our side from `payment` / `refund` rows | Those say what we *think* happened; finance reconciles the books. The ledger is posted in the same transaction, but reading it checks the thing accountants rely on. |
| Parse `journal_entry.description` | Free text; any wording change breaks recon silently. |
| Exact-day matching only | Every capture near midnight would show as missing on both days. |
| Store matched rows too | Volume grows with traffic; the counts are enough, the differences are what someone acts on. |
| CSV report | JSON is typed and the simulator already speaks it; a real acquirer's CSV would just need another `SettlementSource`. |
| Auto-correct differences (post adjustments) | A difference needs a human or a rule set (fees, chargebacks…); recon only reports. |

## Consequences
- + Daily, independent proof that the books and the acquirer agree, with exact references for every difference
  (tested with one of each class, a lost refund, a cross-midnight capture; by hand against the simulator incl.
  a tampered row, an acquirer-only capture, a simulator restart and the simulator down).
- + A refund made at the acquirer but lost by us (Day 7's open item) now shows up as `MISSING_IN_LEDGER`.
- − Reports only: no alert yet (Day 20b metrics), no workflow to resolve items.
- − No fees, chargebacks, payouts or FX in the report (fees/payouts would be a separate payout reconciliation).
- − Two instances could run the same day at 02:00 → two runs (harmless; the latest counts). A scheduler lock if it matters.
- − `/reconciliation/**` has no api-gateway route and no authorization yet (internal ops endpoint).
- − Pre-V9 entries are invisible to reconciliation.
