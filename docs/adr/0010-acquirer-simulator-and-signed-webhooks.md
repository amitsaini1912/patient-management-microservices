# ADR 0010 — payment-network-simulator as our acquirer; signed, deduplicated, forward-only webhooks

- Status: Accepted
- Date: 2026-10-30

## Context
Until now `PaymentProcessor` was an in-memory fake inside billing-service. Real acquirers (Stripe, Razorpay, Adyen)
are remote: calls can time out with an unknown outcome, and they report what happened asynchronously through
webhooks, retried, sometimes twice, in no guaranteed order. A webhook endpoint is also an open door: anyone who
can reach it could claim "payment captured". Decision D2 puts every external party in one service,
`payment-network-simulator`; decision D7 keeps its state in memory.

## Decision
1. **payment-network-simulator** (Spring Boot 3.4.1, port 4010, no DB). `acquirer/`: `POST /v1/authorizations`,
   `/{id}/capture`, `/{id}/void`, `/{id}/refunds`, `GET /{id}`; snake_case JSON, amounts in minor units.
   - `Authorization: Bearer <SIMULATOR_API_KEY>` on `/v1` and `/admin` (constant-time compare).
   - Stripe-style idempotency: a successful call is stored under its `Idempotency-Key`; the same key returns the
     stored result; the same key with a different request → 422. Refusals (409 invalid_state, 422 amount_invalid …)
     aren't stored.
   - Same test cards as the fake (`pm_card_declined`, `pm_card_insufficient_funds`).
2. **Webhooks**: after each successful operation, `payment.authorized | failed | captured | voided | refunded`,
   delivered asynchronously (0.5 s), retried on non-2xx (1 s, 2 s, 4 s …, 5 attempts) with the same event id.
   Test knobs (`/admin/webhooks`): `DUPLICATE` mode (every event twice), `HOLD` + `flush?reversed=true` (out of
   order), `resend`.
3. **Signature**: `Sim-Signature: t=<unix s>,v1=<hex HMAC-SHA256(secret, "<t>.<raw body>")>`, secret from env on both
   sides (`SIMULATOR_WEBHOOK_SECRET` = billing's `GATEWAY_WEBHOOK_SECRET`). billing verifies the **raw bytes**,
   compares with `MessageDigest.isEqual`, and rejects `|now − t| > 5 min`. All failures → the same 401. Billing
   refuses to start without a secret (fail closed).
4. **billing `POST /webhooks/gateway`** (directly, not through api-gateway: the signature is the authentication):
   one transaction does `INSERT webhook_event(id PK) ON CONFLICT DO NOTHING` (0 rows → `DUPLICATE`, 200),
   then `PaymentService.applyGatewayEvent`, then stores the outcome.
5. **Webhooks only move a payment forward**:
   | Our state vs event | Outcome | HTTP |
   |---|---|---|
   | we're behind (our step 3 never committed) | `APPLIED`: same domain methods, ledger posting, outbox event | 200 |
   | already there or past it (normal; duplicates; late events) | `IGNORED_STALE` | 200 |
   | event needs a step we haven't recorded (captured while CREATED) | not stored | 409 → acquirer retries |
   | currency / authorization id doesn't match | `IGNORED_MISMATCH` (logged) | 200 |
   | `payment.refunded` | `RECORDED` only; refund rows come from our own call path, gaps → reconciliation (Day 8) | 200 |
   This closes most of ADR 0005's dual-write gap: if we crash after the acquirer said yes, its webhook catches us up.
6. **Our own step 3 tolerates "already applied"** (and retries on `@Version` conflicts): authorize with the same
   result, capture with the same amount, void. Needed because the webhook can win the race against our own request.
   Side effect: concurrent identical captures with different client keys now **all** get 200 (one capture at the
   acquirer; Day 5 gave the losers 409). Step 3 can't tell a racing request from the webhook, and both mean
   "this capture happened".
7. **Adapter**: `SimulatorPaymentProcessor` (RestClient, connect 2 s / read 5 s), `merchant_reference` = our payment
   id. Any non-2xx or no answer → `PaymentProcessorException` → 502; the client retries with the same key.
   `billing.processor=simulator` by default; tests use `fake` via `src/test/resources/config/application.properties`.

## Alternatives considered
| Option | Why not |
|---|---|
| Trust webhooks without a signature / IP allow-list only | Anyone on the network could mark payments captured. IPs change and are spoofable inside a cluster. |
| Signature over re-serialized JSON | Field order/whitespace differ → valid webhooks rejected, or canonicalization bugs. Raw bytes are exact. |
| `String.equals` for the MAC | Early-exit compare leaks timing; `MessageDigest.isEqual` is constant-time. |
| No timestamp in the MAC | A captured request could be replayed forever. |
| Webhook drives ALL state (synchronous response ignored) | Slower and more moving parts; many PSPs answer synchronously AND send webhooks. We use the response, and the webhook as a safety net. |
| Apply any webhook as "last writer wins" | Late/duplicate events would move payments backwards (captured → authorized). |
| 200 for "too early" events | The acquirer wouldn't resend; the capture would be lost until reconciliation. |
| Return 404 for unknown payments | The acquirer retries a 404 forever; it's not going to start existing. |
| Separate acquirer and network simulators | Decision D2: one service, two packages. |

## Consequences
- + Real HTTP between billing and its acquirer: timeouts, refusals, idempotent retries are exercised for real
  (tested by hand: authorize/capture/refund, decline, DUPLICATE and reversed-HOLD webhooks, forged webhook → 401).
- + The dual-write gap now heals when the webhook arrives (tested: captured-but-not-recorded → `APPLIED`, ledger
  posted, outbox event written).
- − Webhook refunds aren't applied, only recorded. A refund made at the acquirer but lost by us waits for Day 8.
- − Simulator state is in memory: restart = all authorizations gone; billing's later calls on old payments get 404 → 502.
- − One shared HMAC secret, no rotation story yet (Stripe-style: accept two secrets during rotation).
- − `webhook_event` grows forever (retention later), and nothing alerts on 401s (could be an attack or a wrong secret).
- − Webhook endpoint isn't rate-limited (Day 12 is about the gateway; this endpoint bypasses it).
