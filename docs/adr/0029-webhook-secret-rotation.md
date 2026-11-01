# ADR 0029 — Rotating the webhook signing secret without refusing valid webhooks

- Status: Accepted
- Date: 2026-11-01
- Amends: ADR 0010 (one shared HMAC secret, `Sim-Signature: t=…,v1=…`).

## Context
billing verified acquirer webhooks with exactly one secret. Changing it (leaked, staff left, yearly rotation) meant
billing and the acquirer had to switch at the same instant; every webhook in between - and every retry of one signed
earlier - got 401 and was lost after the acquirer's retries. Nothing counted 401s either.

## Decision
- billing accepts **two** secrets: `billing.webhooks.gateway.secret` (current) and `.previous-secret`
  (`GATEWAY_WEBHOOK_SECRET_PREVIOUS`, empty except during a rotation).
- The header may carry **several `v1=` signatures** (up to 4; more is refused), like Stripe: a sender switching
  secrets can sign with both. One valid signature from a configured secret is enough. Every secret x signature pair is
  compared in constant time, without stopping at the first match.
- Counter `billing_webhooks_signature_total{key=current|previous|none}`: `none` = rejected (401), `previous` = the
  rotation isn't finished.

Rotation runbook:
1. billing: `GATEWAY_WEBHOOK_SECRET=<new>`, `GATEWAY_WEBHOOK_SECRET_PREVIOUS=<old>`, restart (rolling).
2. acquirer: switch to `<new>` (the simulator: `SIMULATOR_WEBHOOK_SECRET`).
3. Wait until `key="previous"` stops growing (retries of webhooks signed earlier have drained - the simulator retries
   3 times, real acquirers for up to days), then empty `GATEWAY_WEBHOOK_SECRET_PREVIOUS` and restart.

## Consequences
- During a rotation the old secret still works: a leaked one stays usable until step 3 - keep the window short.
- The timestamp tolerance (5 min) still applies to webhooks signed with either secret.
- The simulator signs with one secret only (it has no rotation of its own); the multi-signature header is tested in
  billing's verifier tests, not end to end.
- Still open: no alert rule on `key="none"` (Prometheus has the counter, no Alertmanager yet).
- Tests: `WebhookSignatureVerifierTest` +2 (current and previous accepted and told apart, two v1= with one valid,
  an older secret refused, no previous configured -> old refused, > 4 signatures refused);
  `GatewayWebhookIntegrationTest` counts the 401 as `key="none"`.
