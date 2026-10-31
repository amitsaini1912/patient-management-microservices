# ADR 0020 — Metrics, a provisioned Grafana dashboard, JSON logs with trace ids

- Status: Accepted
- Date: 2026-10-31
- Builds on: ADR 0019 (tracing). Other side: razorpay-risk-agent ADR 0008 (its JSON logs; its metrics are Day 16).

## Context
Only patient-service exposed Prometheus metrics, nothing was scraped, and logs were plain text without the trace ids
Day 20a introduced: an alert or a slow payment could not be followed from a graph to a log line to a trace.

## Decision
1. **Every Spring service** (gateway, auth, billing, card-vault, simulator, analytics; patient already had it):
   actuator + `micrometer-registry-prometheus`, `/actuator/prometheus`, tag `application=<spring.application.name>`,
   histogram buckets for `http.server.requests` (p95 across instances with `histogram_quantile`). The gateway got
   `spring.application.name`; analytic-service now listens on **4002** (its Dockerfile and k8s already said 4002; it
   ran on Spring's default 8080, so the k8s Service pointed at nothing).
2. **Business metrics in billing**:
   - `billing_payment_events_total{type, reason}`: one per payment event, counted AFTER the transaction commits (a
     rolled-back or version-retried attempt never counts) — payments/s, approval rate, declines by reason;
   - `billing_outbox_pending`, `billing_outbox_oldest_pending_age_seconds` (the lag), `billing_outbox_failed` — read on
     each scrape through partial indexes (V17 adds the FAILED one);
   - `billing_risk_circuit_state` (0 closed / 1 open / 2 half-open), histogram for `billing_risk_duration_seconds`;
     the Day 19a `billing_risk_decisions_total{decision, source, fallback_reason}` gives the fallback rate.
3. **Prometheus 2.55.1 + Grafana 11.3.1 in compose**. Prometheus scrapes the services on the host through
   `host.docker.internal` (host-gateway) and the risk engine in compose; 7 days kept. Grafana is provisioned from
   the repo (datasource + `monitoring/grafana/dashboards/payments-overview.json`, 14 panels: payments/s, approval
   rate, held/s, declines by reason, p95 latency per service + risk engine, fallback rate + reasons, circuit state,
   engine decisions, risk check p95 as billing sees it, outbox lag + failed, feedback events, targets up). Anonymous
   users can view; admin password from `.env`. Edit the JSON, not the UI (UI changes are refused).
4. **`monitoring/check-dashboard.py`** runs every panel query against Prometheus: OK / EMPTY / ERROR.
5. **JSON logs**: Spring Boot 3.4 structured logging, ECS (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`: set in the
   Dockerfiles and by `run-traced.sh`; plain text otherwise). The OTel agent puts `trace_id` / `span_id` in the MDC,
   which ECS writes as fields. **card-vault always logs JSON through its own `MaskedJsonLogFormatter`** (same field
   names; message, MDC, exception message and stack trace masked by PanMasker — Boot's ECS formatter can't mask);
   the old `%maskedMsg` converter is gone.

## Results (real, 2026-10-31)
- billing `mvnw verify`: 383 → **386** (default, alphabetical, reverse): `BillingMetricsIntegrationTest` 3 (counted
  by type/reason, a rolled-back change not counted, outbox/circuit/risk metrics exported). Break check: counting
  inside the transaction → the rollback test fails. card-vault 43 (`MaskedJsonLogFormatterTest` 2 new;
  `LogMaskingTest` still finds no card number in all output); simulator 35, auth 3, gateway 10, analytics 7 green.
- By hand: compose (dbs, kafka, redis, jaeger, risk-engine, prometheus, grafana) + 6 services on the host with the
  agent; 1,000+ gateway payment attempts in 4 phases. `check-dashboard.py`: **16/16 queries OK**. Seen on the
  dashboard data: approval rate 0.37 (one test customer → engine velocity blocks), risk engine paused → breaker OPEN
  (fallback reasons: timeout 13, then circuit_open 144), Kafka stopped 35 s → outbox lag peak 38 s / 140 pending,
  then drained to 0; risk check p95 76 ms (engine) vs 312 ms (fallback, the 300 ms budget). Grafana: dashboard
  provisioned (folder Payments, 14 panels), datasource health OK, anonymous view works.
- Logs: every service JSON; one trace id (`c6125c26…`) found in billing's, card-vault's and the simulator's log
  lines and as a 54-span trace in Jaeger. 0 Luhn-valid card numbers in any service's log.
- Found while testing: my first traffic run was 80% 429s — billing's per-card limit (5, then 1/min), working as
  designed; for the hand run billing ran with `BILLING_RATELIMIT_CARD_CAPACITY=100000` (and the buckets in Redis
  cleared). Note the env name: `billing.rate-limit.*` binds from `BILLING_RATELIMIT_*` (no dash).

## Consequences
- No alerting rules yet (Prometheus rules / Alertmanager): the dashboard shows problems, nobody is paged.
- Outbox gauges run two small SQL queries per scrape.
- Host-run services must be on their usual ports for the scrape config; patient-service shows DOWN unless started.
- card-vault logs JSON even on a developer's terminal (masking matters more than readability there).
