# Rounding rules (billing-service)

Money is stored as a whole number of **minor units** (`long amountMinor` + ISO 4217 currency, ADR 0001). There is
no rounding in storage, addition, subtraction or comparison: those are exact integer operations. Rounding can
only happen where a fraction appears, and there are exactly two such places.

## Where we round

| Where | Code | Rule |
|---|---|---|
| Currency conversion | `Money.convertTo` (via `FxRate.convert`) | multiply in `BigDecimal`, round **once** at the end to the target's minor unit, `HALF_EVEN` |
| Splitting an amount | `Money.allocate(ratios)` | integer division; the leftover minor units go one by one to the first parts, so the parts always add up to the whole |

Nowhere else. In particular:
- **No `double`/`float`** for money or rates, anywhere: `0.1 + 0.2 != 0.3` in binary floating point. Rates are
  `NUMERIC(18,8)` in Postgres and `BigDecimal` in Java; the API takes amounts and rates as **strings** ("84.10"),
  so JSON number parsing can't turn them into doubles. Exponent notation ("8.3e1") is rejected.
- **No rounding of intermediate results.** `100.00 × 83.2550` is computed exactly (8325.5000) and rounded once.

## HALF_EVEN ("banker's rounding")

An exact half goes to the **even** neighbour; everything else rounds to the nearest.

| Exact value | HALF_UP | HALF_EVEN |
|---|---|---|
| 0.825 | 0.83 | **0.82** |
| 0.835 | 0.84 | **0.84** |
| 0.845 | 0.85 | **0.84** |
| 0.855 | 0.86 | **0.86** |

HALF_UP always pushes halves up, so over millions of conversions the total drifts upward (in someone's favour).
HALF_EVEN sends half of the halves up and half down, so the errors cancel out on average. It's the IEEE 754
default and common in finance.

## Minor-unit exponents

How many decimals a currency has comes from `Currency.getDefaultFractionDigits()` (ISO 4217), never hard-coded:

| Currency | Exponent | 1 major unit = | Supported for payments |
|---|---|---|---|
| INR | 2 | 100 paise | yes (book currency) |
| USD | 2 | 100 cents | yes |
| EUR | 2 | 100 cents | yes |
| JPY | **0** | 1 yen (no minor unit) | no; it's in the tests because exponent 0 is where "assume 2 decimals" bugs show |
| BHD, KWD | 3 | 1000 fils | no |

Example with JPY: `USD 1.00 × 151.50 = 151.5 → ¥152` (half, 1 is odd → up to 2), `USD 1.00 × 152.50 → ¥152`.
A system that assumed two decimals everywhere would store ¥152 as 15200 "minor units" and be off by 100×.

Parsing enforces the exponent: `Money.parse("10.005", "INR")` is rejected (half a paisa doesn't exist).

## Conversion in the ledger (ADR 0012)

- **At authorization** the rate is locked on the payment (`payment.fx_rate`). Capture converts at that rate, so the
  patient's INR charge doesn't change if the market moves before capture.
- **Refunds** convert the refunded USD twice: at the locked rate (to reverse the patient's charge exactly as it was
  booked) and at the day's rate (what the USD is worth leaving our position). The difference is posted to
  `FX_GAIN_LOSS`. Rounding differences end up there too; no line is ever "adjusted" to make an entry balance.
- **Convert each transaction, not totals.** `round(a) + round(b)` can differ from `round(a + b)` by a minor unit
  (see `FxRateTest.convertingEachPartThenAddingCanDifferFromConvertingTheTotal`). We convert per capture / per refund
  and let the difference show in FX gain/loss, instead of converting a daily total and having lines that don't
  add up to it.
- **Revaluation** converts each open position once at the latest rate and posts `worth now − carried` to
  `FX_GAIN_LOSS`.
