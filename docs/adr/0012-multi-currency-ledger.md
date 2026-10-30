# ADR 0012 — Multi-currency: INR books, USD/EUR card payments, locked rate, FX clearing and revaluation

- Status: Accepted
- Date: 2026-10-30

## Context
Foreign patients pay by card in USD or EUR; the hospital keeps its books in INR, and their billing accounts (and
receivables) are in INR. The acquirer charges the card in the foreign currency and owes us that currency until
payout. Rates move between authorization, capture, refund and payout. The ledger already requires every journal
entry to balance **per currency** (Day 2, in code and in a DB trigger), so a USD→INR conversion can't be written
as "DR something USD / CR something INR".

## Decision
1. **Rates**: `fx_rate` (base, quote, `NUMERIC(18,8)` rate, source, as_of); the latest with `as_of <= now` wins.
   Seeded USD→INR, EUR→INR; new rates via `POST /fx/rates` (no provider integration). Supported payment currencies:
   `billing.fx.supported-currencies=INR,USD,EUR`; anything else → 400; no rate on file → 422.
2. **Rate locked at authorization** on the payment (`book_currency`, `fx_rate`, `fx_rate_id`). Capture books
   `amount × locked rate` (HALF_EVEN, once). Same-currency payments: rate 1, entries exactly as before Day 9.
3. **Multi-currency ledger with an FX clearing ("currency trading") account per currency** (`FX_CLEARING`, EQUITY):
   - capture USD 100 @83: INR `DR patient 8,300 / CR revenue 8,300`, INR `DR FX clearing 8,300 / CR patient 8,300`,
     USD `DR processor receivable 100 / CR FX clearing 100`. Each currency balances; the clearing pair is our open
     USD position (USD 100) and its INR carrying value (8,300).
   - The acquirer owes us **USD** (`PROCESSOR_RECEIVABLE` USD), so reconciliation (ADR 0011) compares USD with USD
     unchanged.
4. **Refund** USD 50, locked 83, today 85: the patient side is reversed at the **locked** rate (refunds 4,150 /
   patient 4,150), the USD leave the position at **today's** rate (CR FX clearing INR 4,250; USD legs 50), and the
   difference is **realized** FX gain/loss (DR 100 = loss; a falling rate gives a credit = gain). The day's rate is
   stored on the refund.
5. **Revaluation** (`POST /fx/revaluations`, daily 01:00 UTC): one SQL snapshot of all FX clearing balances;
   `worth now = Σ position × latest rate`; `delta = worth now − INR carried` → `FX clearing INR / FX_GAIN_LOSS`
   (unrealized). Serialized with a Postgres advisory lock (two concurrent runs would both post the same delta).
   Idempotent: a second run with the same rates posts nothing.
6. Rounding rules in `docs/ROUNDING.md`.

## Alternatives considered
| Option | Why not |
|---|---|
| INR-only books (acquirer settles INR; refund difference straight to FX gain/loss) | Simpler, offered and declined: it hides the USD the acquirer actually owes us, and recon would compare INR estimates with USD reports. |
| Convert at capture-time rate (no lock) | The patient's INR charge would change between authorization and capture; disputes and confusing statements. |
| Refund fully at the locked rate | Ignores that the USD returned cost more/less INR today: the books would claim no FX effect when there was one. |
| A single cross-currency entry with a "rate" column | Breaks the per-currency balance rule that the DB enforces; balances per currency become meaningless. |
| `double` rates | Binary floating point can't represent 83.10 exactly. |
| Revalue on every rate insert | Couples rate entry to ledger postings; a daily close (and on demand) is how books are normally marked. |

## Consequences
- + Every currency balances on its own (tested: trial balance zero in INR and USD after captures, refunds, revaluations).
- + The USD exposure is visible as a balance (`FX_CLEARING` USD) and marked to market daily; gains/losses split into
  realized (refunds) and unrealized (revaluation).
- + Locked-rate captures, loss on rate rise, gain on rate fall, idempotent and race-safe revaluation: all tested; by
  hand: USD 100 @83, refund 50 @85 → ₹100 realized loss, revaluation +₹200, net ₹100 gain = 50 × (85 − 83).
- − **Payout not modelled**: when the acquirer pays out USD (or converts to INR), the USD position should close
  (`DR bank / CR processor receivable`, realized FX vs the carried value). No bank account in the ledger yet.
- − Book currency is configured (`billing.fx.book-currency=INR`); billing accounts in another currency would need
  their own system accounts and a per-book revaluation.
- − Rates are entered by hand; no provider, no bid/ask spread, no rate staleness check.
- − `PaymentEvent` (Kafka) still carries amounts in the payment currency only (no locked rate / INR amount).
