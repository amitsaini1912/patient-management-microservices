# ADR 0016 — Tamper-evident audit log: one HMAC hash chain, appended in the business transaction

- Status: Accepted
- Date: 2026-10-30

## Context
Auditors (and PCI DSS req. 10) want to know who changed what and when, and they want proof that this record
wasn't edited afterwards. Rows in a normal table can be changed by anyone with write access to the database,
without a trace. The ledger is already append-only (ADR 0003), but payment status changes, refunds and manually
entered FX rates had no "who" and no protection.

## Decision
1. **`audit_log` (V13)** in billing: `seq` (1, 2, 3 … no gaps), `occurred_at` (µs), `actor`, `action`,
   `entity_type`, `entity_id`, `details` (canonical JSON, stored as TEXT so the exact bytes can be re-hashed),
   `prev_hash`, `hash`.
2. **Hash chain, keyed**: `hash = HMAC-SHA256(key, prev_hash, seq, occurred_at, actor, action, entity_type,
   entity_id, details)` with every field length-prefixed (`len:value`), so moving text between fields can't produce
   the same input. The first row's `prev_hash` is 64 zeros.
   - **HMAC instead of the plan's plain SHA-256**: with a plain hash, someone with DB write access can change a row
     and recompute every hash after it. The key (`BILLING_AUDIT_KEY`, ≥ 32 bytes) is not in the database.
3. **Serialized appends**: one row `audit_chain_head(last_seq, last_hash)` is locked `FOR UPDATE` by every append,
   until commit, so two writers can never build on the same `prev_hash`. `seq` comes from the head, not from a
   SEQUENCE: a sequence skips numbers on rollback, and a gap has to mean "a row was deleted".
4. **Same transaction as the change** (`Propagation.MANDATORY`): written from `PaymentEvents` (every payment change,
   next to its outbox event) and `FxRateService.record`. A rolled-back change leaves no audit row and no gap.
   **Lock order**: `append` flushes pending JPA changes BEFORE locking the head, so every transaction locks payment
   rows first, then the head. The opposite order deadlocks (reproduced in a test: "deadlock detected" without the flush).
5. **Actor**: `user:<X-User-Id>` (from the gateway, ADR 0015), `acquirer` (webhooks), `internal` (HTTP without
   the gateway), `system` (scheduled jobs). Details: amounts, status, codes, last4/brand. Never a PAN or a token.
6. **Append-only trigger** on `audit_log` (UPDATE/DELETE/TRUNCATE refused) and on deleting the head. The chain is for
   whoever can switch the trigger off.
7. **`GET /audit/verify`** walks the chain oldest-first in pages of 500, up to the head as read at the start (no lock;
   concurrent appends aren't blocked), and reports the first broken row: missing `seq`, wrong `prev_hash`, content ≠
   HMAC, or chain shorter than the head. `GET /audit?entityType=&entityId=` shows one entity's history.
   Internal ops endpoints, no gateway route.

## Consequences
- Every payment-changing transaction now passes one global lock (the head) just before commit. Fine at our volume;
  at scale: per-partition chains, batching (Merkle trees), or appending from the outbox asynchronously.
- Billing needs `BILLING_AUDIT_KEY` to start.
- Limits (honest):
  - **DB access alone can silently cut off the newest rows**: delete the last rows AND move the head back to the
    new last row — every remaining hash is still valid (shown by hand on Day 13). Only an outside copy of the head
    catches this: publish the head hash regularly where the DBA can't write ("anchoring": another system, Kafka,
    a signed log). Editing, deleting or inserting rows in the middle IS caught without that.
  - Someone with the key AND DB access can rewrite the whole chain and head (same fix: anchoring, or a KMS/HSM key).
  - No key rotation (changing the key breaks verification of old rows; needs a key id per row); `verify` is O(n)
    every time (checkpoints later); the ledger itself and billing-account changes over gRPC aren't in the chain;
    no retention policy.
