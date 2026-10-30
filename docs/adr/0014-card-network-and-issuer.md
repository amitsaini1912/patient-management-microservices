# ADR 0014 — Card network + issuer inside the acquirer; ISO 8583-style messages; 91 = retryable decline

- Status: Accepted
- Date: 2026-10-30

## Context
Until Day 10 the simulated acquirer decided approve/decline by looking at the token string (`pm_card_declined`).
A real card payment goes merchant → acquirer → card network (Visa/Mastercard) → issuing bank. The acquirer turns the
merchant's request into an ISO 8583 authorization (MTI 0100) carrying the card number, and the issuer answers with
a response code (field 39). We want that chain, its response codes, and the two failure cases that make card
payments hard: an issuer that is down, and an issuer that answers too late.

The plan said "billing authorize/void go through the network (`PaymentProcessor` impl #3)".

## Decision
1. **The network lives behind the acquirer, inside payment-network-simulator (option A: recommended on 2026-10-30;
   no option was picked, so A was the default — revisit if B is wanted).**
   Billing keeps calling the same acquirer HTTP API (`SimulatorPaymentProcessor`); there is no third
   `PaymentProcessor`. Merchants never speak ISO 8583; acquirers do. Billing never gets nearer to the PAN.
   Packages: `acquirer/` (PSP API), `network/` (switch + messages), `issuer/` (the bank), `card/` (vault client).
2. **Authorization path:** acquirer → card-vault `POST /cards/{token}/detokenize` (the simulator's key has
   DETOKENIZE, ADR 0013) → `0100{PAN, amount, STAN, RRN, expiry}` → network adds field 6 (amount in the card's
   billing currency, fixed scheme rate, HALF_EVEN) → issuer → `0110{STAN, RRN, approval code, field 39}`.
   The PAN exists only in that call; message `toString()` and the message log mask it (`****1111`).
   Vault down → acquirer 503 (nothing was sent, same key retries); unknown token → 422.
3. **Response codes → merchant view:**
   | 39 | meaning | acquirer status / `decline_reason` | `retryable` | billing payment |
   |---|---|---|---|---|
   | 00 | approved | AUTHORIZED | false | AUTHORIZED |
   | 05 | do not honor | DECLINED `card_declined` | false | FAILED |
   | 51 | insufficient funds | DECLINED `insufficient_funds` | false | FAILED |
   | 54 | expired card | DECLINED `expired_card` | false | FAILED |
   | 91 | issuer unavailable / timeout | DECLINED `issuer_unavailable` | **true** | FAILED, `retryable=true` |
   Billing stores `network_response_code` + `retryable` (V12; DB CHECK: only a FAILED payment can be retryable) and
   returns both in the API and reads them from `payment.failed` webhooks.
4. **91 is a final answer for THIS payment.** FAILED stays terminal and the decline is stored under the
   Idempotency-Key (acquirer and billing), so the same key replays the FAILED payment. A retry is a new payment with
   a new key. (Real scheme retry rules are finer — e.g. Visa allows limited retries on 51 — we only flag 91.)
5. **Timeouts:** the network waits `simulator.network.issuer-timeout` (1s, well below billing's 5s read timeout).
   No 0110 in time → the network answers 91 itself (stand-in) **and sends a 0400 TIMEOUT reversal**: the issuer may
   have approved and held the money, and nobody will ever capture it. The issuer call is not cancelled.
6. **Reversals (0400/0410):** matched by RRN + original STAN, no PAN. Void (and therefore billing's expiry job)
   sends one: the hold is released. Issuers acknowledge every reversal with 00, also a duplicate. A reversal that
   **overtakes its own 0100** is remembered by RRN, so the late 0100 places no hold.
7. **Clearing:** capture → the hold becomes a debit, the rest of a partial capture is released; refund → credit.
   Done at once in memory (in real life: batch clearing files the next day, not ISO 8583 0100-family messages).
8. **Issuer** in memory (D7): balance + holds per card, one lock. Test cards (Luhn-valid, so the vault accepts
   them): 4111111111111111 / 5555555555554444 / any other card → 00 (51 above available); 4000000000000002 → 05;
   4000000000009995 → 51 (balance 0); 4000000000000069 → 54 (cancelled 01/2020, whatever the vault says);
   4000000000000911 → 91 (host down); 4000000000007775 → approves and holds, answers after 3s → 91 + reversal.
   `simulator.card-vault.mode=fake` maps `pm_card_*` tokens to these cards (tests, runs without the vault).

## Consequences
- Day 7–10 flows are unchanged for billing; the decline reasons for 05/51 are the same words as before.
- The simulator now needs card-vault (or `SIMULATOR_CARD_VAULT=fake`) and `CARD_VAULT_KEY_SIMULATOR`.
- New ops views: `GET /admin/network/messages`, `GET /admin/issuer/accounts` (API key, PANs masked).
- Not modelled: binary ISO 8583 / bitmaps, 0120/0220 advices, stand-in approvals, scheme fees and interchange,
  daily scheme rates, network-side retries of 0400 (in memory it can't get lost), issuer-side hold expiry,
  `PaymentEvent` (Kafka) doesn't carry the response code yet.
