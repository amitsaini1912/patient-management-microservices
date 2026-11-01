# ADR 0031 — Reconciliation and FX endpoints: gateway routes + ADMIN only

- Status: Accepted
- Date: 2026-11-01
- Closes PLAN weakness #8 (roles in the JWT, checked nowhere) for the last unprotected endpoints.

## Context
`/reconciliation/runs` (Day 8) and `/fx/rates`, `/fx/revaluations` (Day 9) had no gateway route and no role check:
anyone who could reach billing's port could start a reconciliation, read the books against the acquirer's
settlement, or record an exchange rate - which changes what every later foreign payment books. Payment reviews and
chargebacks were already ADMIN only (Day 19), through `X-User-Role`, which the gateway sets from auth-service's
answer and strips from the client's request (Day 12).

## Decision
- billing: one helper `web.Roles.requireAdmin` (moved out of PaymentController). ADMIN only: `POST
  /reconciliation/runs`, `GET /reconciliation/runs/{date}`, `POST /fx/rates`, `POST /fx/revaluations`.
  `GET /fx/rates/{base}/{quote}` stays open (a rate is not secret; checkout UIs may show it). 403 otherwise; the
  check is case-sensitive (`admin` is not `ADMIN`).
- gateway: `billing-service-ops-route` for `/api/reconciliation/**` and `/api/fx/**` with JwtValidation (401 without a
  token); no rate limit (a few admin calls a day).
- The scheduled jobs (02:00 reconciliation, 01:00 revaluation) call the services directly, not HTTP: unaffected.

## Consequences
- Same trust boundary as before: the role is only as good as "billing is reachable only through the gateway"
  (compose still publishes 4001 for development; k8s keeps it ClusterIP-only).
- Roles are two values (USER / ADMIN); no finer permissions (e.g. "finance" vs "support").
- Tests: billing `ReconciliationIntegrationTest` +1 (no role / USER / lower-case admin -> 403 for run and read),
  `FxIntegrationTest` +1 (USER -> 403 for rate + revaluation, read without a role -> 200); existing tests send ADMIN.
  gateway `GatewayIntegrationTest` +1 (401 without a token, routed paths, forged ADMIN replaced by the token's role).
  Break check: without the check on the run -> the test fails.
