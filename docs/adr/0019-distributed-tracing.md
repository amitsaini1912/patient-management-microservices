# ADR 0019 — Distributed tracing: OpenTelemetry Java agent, W3C context through HTTP, gRPC, Kafka and the outbox

- Status: Accepted
- Date: 2026-10-31
- Other side: razorpay-risk-agent ADR 0007 (FastAPI + Kafka consumer spans).

## Context
A payment touches up to seven processes (gateway, auth, billing, card-vault, the acquirer/network simulator, the risk
engine, analytics) and two kinds of hop: HTTP calls and Kafka events published later by billing's outbox relay
(ADR 0008), on another thread. When a payment was slow or wrong there was no way to see where.

## Decision
1. **OpenTelemetry Java agent 2.10.0** for every Java service — no code: it instruments Spring MVC + WebFlux
   (gateway), RestClient/RestTemplate, the JDK HttpClient (billing → risk), gRPC, Kafka producer/consumer, JDBC,
   Hibernate, Redis. W3C `traceparent` everywhere; OTLP/HTTP export.
   - Host runs: `sh monitoring/otel/run-traced.sh <service> <jar>` (downloads the agent once, SHA-256 checked;
     the jar is gitignored; per-service settings from `monitoring/otel/<service>.properties`).
   - Images: the 5 Dockerfiles `ADD --checksum=sha256:...` the agent and set `JAVA_TOOL_OPTIONS`;
     `OTEL_SDK_DISABLED=true` by default — set `false` + `OTEL_EXPORTER_OTLP_ENDPOINT` to export.
   - Traces only (`OTEL_METRICS_EXPORTER=none`, `OTEL_LOGS_EXPORTER=none`): metrics and logs are Day 20b.
2. **Through the outbox** (the only code change, billing): `outbox_event.trace_parent` / `trace_state` (V16) store
   the context of the request that wrote the event; the relay sends it as the `traceparent` header and makes it
   current while sending, so the agent's `publish` span — and every consumer's `process` span — join the
   request's trace. Only the OpenTelemetry API (1.44.1, matches the agent); without the agent it does nothing.
3. **No poll traces**: the relay polls every 0.5 s. Its polls run under an UNSAMPLED parent (nothing under it is
   recorded), and billing turns off the agent's `spring-scheduling` spans. Found by hand: 374 poll traces in 20
   minutes buried the payment traces; after the fix, 0.
4. **Jaeger all-in-one 1.62.0** in compose (`docker compose up -d jaeger`, UI http://localhost:16686, OTLP 4317/4318),
   in memory.

## Results (real, 2026-10-31)
- billing `mvnw verify`: 379 → **383** (default, alphabetical, reverse): `OutboxTraceContextIntegrationTest` 4 — a
  request span's traceparent is stored with the row and arrives as the Kafka header (relay thread, later); no span →
  nothing stored or sent; a stored context is the parent again; nothing under the poll context is recorded.
- Dockerfile `ADD --checksum` line built and the agent started (2.10.0) in a throwaway image; the full service images
  were not rebuilt.
- By hand, 7 services traced (gateway, auth, billing, card-vault, simulator, analytics on the host with the agent;
  the risk engine in compose), real gateway login + JWT:
  - tokenize: gateway → auth (/validate) → card-vault;
  - authorize, one trace (47 spans): gateway → auth → billing → card-vault (metadata) → risk-engine `/v2/assess`
    (`risk.features`, `risk.decide`, `risk.store`) → outbox → `payment.events publish` → analytics `process`;
  - authorize after review approval / capture: … → simulator `/v1/authorizations` → card-vault `detokenize` →
    simulator webhook → billing `/webhooks/gateway` → `payment.events` + `payment.outcome publish` → analytics and
    the risk engine's `payment.outcome process` — one trace id across 7 services.
- Found from the traces: a `/v2/assess` without the engine's spans — the engine had answered 422: billing's payment
  UUID `f2fdd154-9879-3051-96c0-…` contains `154-9879-3051-96`, 13 digits that pass Luhn, so the engine's PAN guard
  refused it and billing used its fallback. 0.22% of UUIDs; fixed in the engine (its ADR 0007).

## Consequences
- The agent adds ~20 MB per image and a few seconds to startup.
- Jaeger keeps traces in memory only; no sampling yet (every request traced) — fine locally, not for load tests
  (Day 22 should set `OTEL_TRACES_SAMPLER=parentbased_traceidratio`).
- One-off root spans remain: startup SQL (Flyway/Hibernate) and the Redis connection handshake (`HELLO`/`CLIENT`).
- With `spring-scheduling` spans off, billing's other jobs (auth expiry, reconciliation, cleanup) have no root span:
  their SQL shows as separate small traces.
- Gateway and services must keep forwarding headers (Spring Cloud Gateway does); a proxy that strips `traceparent`
  would split traces.
