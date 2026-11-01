# ADR 0026 — Warm up the risk client before billing reports ready

- Status: Accepted
- Date: 2026-11-01
- Evidence: `docs/CHAOS.md` (every live run after a billing restart started with ~30 s of fallback decisions).

## Context
Right after billing starts, the first risk checks miss the 300 ms budget: new TCP connections, classes loaded and code
run for the first time. 7 timeouts open the risk breaker (ADR 0017), and real payments get billing's fallback rules
for ~30 s. Nothing told the orchestrator billing wasn't really ready: health was UP as soon as Tomcat listened.

## Decision
- `RiskEngineWarmup` (an ApplicationRunner, only with `billing.risk.enabled=true`): 10 GETs of the engine's `/ready`
  through the SAME `HttpClient` the checks use, plus one round of the client's JSON writing and reading. No payment is
  sent, so the engine stores and counts nothing (no velocity, no decision). Any HTTP answer counts; it stops at the
  first unreachable call and never fails startup (`billing.risk.warm-up.calls`, `.timeout`, default 10 / 1 s).
- Readiness probes on (`management.endpoint.health.probes.enabled=true`). Spring Boot moves readiness to
  ACCEPTING_TRAFFIC only after the runners, so `/actuator/health/readiness` - and the root `/actuator/health`, which
  includes it and is what `load-test/start-stack.sh` polls - answer 503 until the warm-up is done.
  k8s `billing-service.yaml` gets readiness and liveness probes.
- The risk client speaks HTTP/1.1 explicitly: the JDK default (HTTP/2) adds an h2c upgrade to the first request on each
  plain-http connection, which uvicorn doesn't use.

## Consequences
- Startup is longer by the warm-up (measured in the test against a local stub: first call 56 ms, last 1 ms; at most
  10 s if the engine hangs).
- Only billing's side is warmed: `/ready` doesn't run the engine's model, so a freshly started ENGINE can still be slow
  on its first assessments. Code billing runs per payment (JPA, ledger) isn't warmed either - it needs no budget.
- Not re-measured live (restart billing under k6 and count fallback decisions in the first minute).
- Tests: `HttpRiskEngineClientTest` +3 (warm-up = GET /ready only, no key, no body, no upgrade header; assess has no
  upgrade header; engine absent -> gives up at once), `RiskCheckIntegrationTest` +1 (stub engine saw >= 10 /ready calls
  before the context was ready; root health includes readinessState).
