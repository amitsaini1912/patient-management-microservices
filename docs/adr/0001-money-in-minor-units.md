# ADR 0001 — Money is a `long` of minor units; BigDecimal only for FX

- Status: Accepted
- Date: 2026-10-29

## Context
billing-service is becoming a payments service. Every amount will be added, split, refunded and
reconciled many times. `double`/`float` cannot represent 0.10 exactly (`0.1 + 0.2 = 0.30000000000000004`),
so errors build up and a ledger stops balancing by a paisa. Amounts also need a currency: ₹100 and $100
must never be added together.

## Decision
- A `Money` value object (Java record): `long amountMinor` + `java.util.Currency`. ₹12.34 = `1234`.
- No constructor or factory accepts `double`. Input from outside is a decimal **string** parsed with
  `BigDecimal` and `RoundingMode.UNNECESSARY` — too many decimals is an error, never a silent round.
- `plus/minus/negate` use `Math.addExact` etc., so overflow throws instead of wrapping to a negative number.
- Different currencies in one operation → `CurrencyMismatchException`.
- Splitting (`allocate`) gives leftover minor units to the first parts so the parts always sum to the total.
- `BigDecimal` is used **only** for currency conversion, rounded **once** at the end with `HALF_EVEN`.
- Database columns: `BIGINT amount_minor` + `VARCHAR(3) currency`.

## Alternatives considered
| Option | Why not |
|---|---|
| `double` | Not exact. Rejected outright. |
| `BigDecimal` everywhere | Exact, but scale must be managed on every operation (`2.0` vs `2.00` are not `equals`), slower, and easy to round in the wrong place. Fine for FX math, too loose as the storage type. |
| `NUMERIC(19,4)` in DB | Works, but invites fractional paise that can never be paid out. |
| Joda-Money / JSR-354 (Moneta) | Good libraries, but an extra dependency for ~150 lines we can fully explain and test. |

## Consequences
- + Exact arithmetic, cheap, easy to compare, maps 1:1 to `BIGINT`.
- + The same convention Stripe's API uses (`amount` in the smallest currency unit).
- − Every API boundary must convert (decimal string ⇄ minor units). The exponent comes from
  `Currency.getDefaultFractionDigits()` (INR/USD/EUR = 2, JPY = 0, XAU = -1 → rejected).
- − Max ≈ 9.2 × 10^18 minor units. Far above any real payment; overflow throws.
