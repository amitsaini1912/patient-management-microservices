# ADR 0007 — `Idempotency-Key` on every money-moving POST: Postgres claim row + idempotent payment steps

- Status: Accepted
- Date: 2026-10-30

## Context
Clients (and the gateway) retry on timeouts. Without protection a retried `POST /payments` authorizes the card twice,
and a retried refund sends money back twice. ADR 0005 also left a dual-write gap: the processor call succeeds and the
DB write after it fails (crash, DB down). The client can't tell "it failed" from "it worked but the answer was lost".
It needs a way to say "this is the same request again", and we need to answer it without moving money a second time.

## Decision
1. **Header `Idempotency-Key` required on every `POST /payments/**`** (authorize, capture, void, refunds).
   8–255 chars of `[A-Za-z0-9_\-:.]`, i.e. a UUID. Missing or malformed → 400. GETs don't need it.
2. **A servlet filter (`IdempotencyFilter`) + table `idempotency_key`**, PK `(scope, idempotency_key)`, columns:
   SHA-256 of `method + path + body`, status `IN_PROGRESS`/`COMPLETED`, stored status/content-type/Location/body,
   `locked_at`, `expires_at` (TTL 24h).
   | Situation | Response |
   |---|---|
   | new key | run it; store the response |
   | same key + same request, finished | replay byte for byte, header `Idempotent-Replayed: true` |
   | same key + same request, still running | 409 + `Retry-After: 1` |
   | same key + different body **or path** | 422 |
   | same key, owner silent > lock timeout (60s) | the retry takes the key over and runs it |
3. **Claim with one atomic statement**: `INSERT … ON CONFLICT DO NOTHING` (or `DO UPDATE … WHERE expires_at <= now`
   to reuse an expired row). No "SELECT, then INSERT". Each store statement auto-commits, so the claim is visible to
   other requests at once, and no DB transaction stays open during the business request.
4. **What is stored**: 2xx and deterministic 4xx (400/404/409 wrong state/422). **Not stored** (key deleted so the
   same key can retry): 5xx, uncaught exceptions, and the `@Version` 409 (marked retryable by the exception handler).
5. **`locked_at` is a fencing token**: `complete`/`release` only match while the row still has the owner's
   `locked_at`. An owner that ran past the lock timeout and lost the key can't overwrite or delete the new owner's row.
6. **The payment code is idempotent on its own** (the filter only stops duplicates; a released or taken-over key
   re-runs the request):
   - authorize: payment id = `UUID.nameUUIDFromBytes("payment:" + key)`. A retry finds the row: if it's still
     CREATED it finishes it, otherwise it returns it.
   - refund: refund id = `UUID.nameUUIDFromBytes("refund:" + key)`. If the row exists, return it.
   - every processor call carries a key: `authorize:<paymentId>`, `capture:<paymentId>:<amount>`,
     `void:<paymentId>`, `refund:<refundId>`. The processor (like Stripe/Adyen) returns the first result for a
     repeated key. Capture and void are once per payment, so their key comes from the payment, not the client.
     Our void and the expiry job's void therefore share one key.
7. **Scope** is one constant (`default`) until the gateway forwards a caller id (Day 12). Keys must be unguessable
   (UUIDs), or one client could replay another's response.
   *Update Day 12 (ADR 0015): requests through the gateway are scoped per user (`X-User-Id`); `default` remains
   for callers without it.*
8. **Cleanup**: `@Scheduled` hourly `DELETE … WHERE expires_at < now` (index on `expires_at`). Safe on many instances.

## Alternatives considered
| Option | Why not |
|---|---|
| Unique constraint on a business field (e.g. order id) | We have no natural unique field; one patient can pay the same amount twice for real. |
| SELECT then INSERT | The check-then-insert race. Proven: with it, 15 of 16 concurrent duplicates got 500 (PK violation). |
| Hold the idempotency row lock (`SELECT … FOR UPDATE`) for the whole request | Duplicates block a DB connection each for seconds; a slow processor exhausts the pool. 409 + retry is cheaper. |
| Redis `SET NX` | Extra infrastructure; Redis and Postgres can disagree after a failover. The same Postgres that holds the payment is simpler. |
| Store the key on the payment row only | Covers authorize, but not "same key, different body", replaying 4xx, or capture/refund. |
| Store 5xx responses too | A temporary DB blip would be replayed forever; the client could never retry with that key. |
| Random payment/refund ids | A retry after a crash would create a second payment. Proven: 4 retry tests fail. |
| `@Transactional` around claim + business + complete | One long transaction: nobody else sees the claim until commit, so duplicates aren't detected; and ADR 0005 forbids network calls inside a transaction. |

## Consequences
- + Retries are safe end to end. Tested: 16 concurrent identical authorizes → one payment, one processor call;
  16 concurrent identical refunds → one refund; crash after processor / before DB → the retry finishes without
  moving money again.
- + Closes Day 4a's open item: concurrent captures with different keys now give one 200 and otherwise 409 (they used
  to give 502 because the processor refused the second capture).
- + Covers the dual-write gap when the client retries. If it never retries, reconciliation (Day 8) still has to.
- − One extra INSERT + UPDATE per POST (small, by PK).
- − Responses are kept 24h in `response_body` (payment JSON: ids, amounts, status; no card data). Anything added
  to responses later is stored there too.
- − Payment/refund ids are tied to the key forever: a key reused after its 24h TTL returns the old payment, not a new
  one. That's arguably safer; it's documented for clients.
- − Lock timeout (60s) must stay longer than the slowest request including processor timeouts, or two owners can run
  at once (fencing stops only the response overwrite, not the double run; the processor key stops the double charge).
- − Single global scope until callers are identified (Day 12).
