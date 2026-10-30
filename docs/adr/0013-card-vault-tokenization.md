# ADR 0013 — card-vault: tokenization with AES-256-GCM; billing never sees a card number

- Status: Accepted
- Date: 2026-10-30

## Context
Card payments need the card number (PAN) somewhere: the card network has to see it (Day 11). Every system that
stores, processes or even receives a PAN is in PCI DSS scope (audits, network segmentation, key management, logging
rules). The goal is to keep that set as small as possible: billing, its database, its logs, Kafka and analytics
should never contain a PAN.

## Decision
1. **card-vault**: a separate Spring Boot service (port 4020) with its **own Postgres** (`vault-db`, separate
   credentials) and three operations:
   | Endpoint | Permission | Caller |
   |---|---|---|
   | `POST /cards` (PAN, expiry) → token + metadata | TOKENIZE | api-gateway (on behalf of the patient app) |
   | `GET /cards/{token}` → last4, brand, fingerprint, expiry | READ_METADATA | billing |
   | `POST /cards/{token}/detokenize` → PAN | DETOKENIZE | payment-network-simulator only (Day 11) |
   One `X-Api-Key` per caller (env, constant-time compare): no/unknown key → 401, known key without the
   permission → 403. Every detokenization is written to `detokenize_audit` in the same transaction.
2. **PAN path** (your choice): app → api-gateway `POST /api/cards` (JWT) → vault; the gateway injects its own key
   (`SetRequestHeader`, so a client can't bring another). Only that one route exists: metadata and detokenize are
   not reachable from outside (tested: 404). The app then pays with the **token**; billing reads metadata and
   stores token/last4/brand/fingerprint on the payment.
3. **Encryption**: AES-256-GCM, a fresh random 12-byte IV per encryption, 128-bit tag, **the token as associated
   data** (a ciphertext copied onto another row fails). Keys: `VAULT_ENCRYPTION_KEYS="1:<b64>,2:<b64>"` +
   `VAULT_ACTIVE_KEY_VERSION`; each row stores its `key_version`. Rotation = add a version, make it active; old rows
   stay readable. Any tampering (data, IV, tag, key version, row swap) → "card data unreadable" (500), never garbage.
4. **Fingerprint** = `HMAC-SHA256(VAULT_FINGERPRINT_KEY, PAN)`: same card → same fingerprint across tokens
   (duplicate detection), not reversible without the key (a plain SHA-256 over ~10^15 possible PANs is). Never rotated.
5. **Tokens**: `tok_` + 128 random bits in lowercase base32, **never 13+ digits in a row**. New token per
   tokenization (your choice), even for a known card.
6. **Validation**: digits (spaces/dashes allowed), 13–19 long, Luhn, expiry not past; brand from a simplified BIN
   table (Visa, Mastercard incl. 2-series, Amex, RuPay, Discover). Error messages never contain the number; no bean
   validation on the PAN (its messages echo the rejected value); Jackson errors replaced with a generic 400.
7. **Logs**: two layers. (a) No PAN in code paths (`toString` of request/response records redacted). (b) Every log
   line (message + stack trace) goes through `PanMaskingConverter`: any standalone 13–19 digit Luhn-valid run →
   `[PAN ****1234]`. `LogMaskingTest` exercises every path, logs PANs deliberately, and scans all output with an
   independent implementation.
8. **billing**: `CardVaultClient` (HTTP, key with READ_METADATA only; a fake in tests). Unknown token or expired
   card → 422 before anything is charged; vault unavailable → 503, idempotency key released (retry with the same key).

## Alternatives considered
| Option | Why not |
|---|---|
| PAN through billing (billing tokenizes) | Offered and declined: billing (and its request handling, logs, heap) would be in PCI scope. |
| Encrypt the PAN in billing's own DB | Everything with DB access or the key is in scope; no separation of duties. |
| AES-CBC + HMAC / AES-ECB | CBC needs a separate MAC done right; ECB leaks patterns. GCM is authenticated encryption in one primitive. |
| Deterministic encryption (to find duplicates) | Same PAN → same ciphertext leaks equality to anyone with DB access; the HMAC fingerprint gives duplicate detection without weakening the ciphertext. |
| Plain SHA-256 fingerprint | Brute-forceable: BIN + 9 digits + Luhn is a small space. |
| Token = sequential id / UUID | Sequential leaks volume and invites enumeration; hex UUIDs can contain Luhn-valid digit runs (measured below). |
| One shared vault key for all callers | Can't express "billing may never detokenize". |
| Real KMS/HSM | Right for production (keys wrapped by a KMS key, decrypt in the HSM); env-var keys are this project's stand-in. |

## Consequences
- + PAN found in exactly one place: tested by an end-to-end run where `pg_dump` of billing-db and vault-db and all
  four service logs contain the test PAN **0 times**; billing's key gets 403 for tokenize and detokenize.
- + Rotation without downtime; tampering detected; every PAN read audited.
- − **Found on Day 10**: 0.61% of hex tokens contained a Luhn-valid 13+ digit run, which the log masker would hide as
  a PAN (0.17%). Fixed by base32 tokens with a "no 13 digits in a row" guarantee. Testcontainers' 64-hex container
  ids can still be partly masked in test logs (harmless).
- − No re-encryption job yet: rows keep their old key version until rewritten; a key can only be removed from the
  ring once no row uses it (`card_key_version_idx` makes that query cheap).
- − Keys in env vars: readable by anyone with process/host access. KMS envelope encryption is the production answer.
- − No rate limiting on tokenization (card-testing attacks); no card expiry clean-up; brand table simplified.
- − The gateway route can only be checked partly locally (no auth-service): unauthenticated → 401, other paths → 404.
