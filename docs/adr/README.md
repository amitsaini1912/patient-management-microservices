# Architecture Decision Records

One file per important decision: what we chose, what else we considered, and what it costs us.
Format: Context → Decision → Alternatives → Consequences. ADRs are never edited after acceptance;
a new ADR supersedes an old one.

| # | Decision | Status |
|---|---|---|
| [0001](0001-money-in-minor-units.md) | Store money as `long` minor units, BigDecimal only for FX | Accepted |
| [0002](0002-billing-on-spring-boot-3-4.md) | billing-service on Spring Boot 3.4.1 like the other services | Accepted |
| [0003](0003-double-entry-ledger.md) | Double-entry, append-only ledger enforced in code and in Postgres | Accepted |
| [0004](0004-payment-state-machine-optimistic-locking.md) | Payment state machine as an explicit transition table + `@Version` optimistic locking | Accepted |
| [0005](0005-payment-api-processor-calls-and-postings.md) | Payment API: processor calls outside DB transactions; ledger posts only when money moves | Accepted |
| [0006](0006-refunds-and-authorization-expiry.md) | Refunds as records with mirror postings; authorization expiry via `FOR UPDATE SKIP LOCKED` | Accepted |
| [0007](0007-idempotency-keys.md) | `Idempotency-Key` on every money-moving POST: Postgres claim row + idempotent payment steps | Accepted |
| [0008](0008-transactional-outbox.md) | Payment events via a transactional outbox; protobuf payload; one ordered relay | Accepted |
| [0009](0009-idempotent-consumer-and-dlt.md) | analytic-service: idempotent payment-event consumer + dead-letter topics | Accepted |
| [0010](0010-acquirer-simulator-and-signed-webhooks.md) | payment-network-simulator as our acquirer; signed, deduplicated, forward-only webhooks | Accepted |
| [0011](0011-daily-reconciliation.md) | Daily reconciliation of the ledger against the acquirer's settlement report | Accepted |
| [0012](0012-multi-currency-ledger.md) | Multi-currency: INR books, USD/EUR card payments, locked rate, FX clearing and revaluation | Accepted |
| [0013](0013-card-vault-tokenization.md) | card-vault: tokenization with AES-256-GCM; billing never sees a card number | Accepted |
| [0014](0014-card-network-and-issuer.md) | Card network + issuer inside the acquirer; ISO 8583-style 0100/0400; 91 = retryable decline | Accepted |
| [0015](0015-rate-limiting.md) | Rate limiting: per user at the gateway, per card in billing; callers identified from the JWT | Accepted |
| [0016](0016-tamper-evident-audit-log.md) | Tamper-evident audit log: one HMAC hash chain, appended in the business transaction | Accepted |
| [0017](0017-risk-check-before-authorization.md) | Risk check before authorization: 300 ms budget, one retry, circuit breaker, fail open / to review; ADMIN review | Accepted |
| [0018](0018-chargebacks-and-risk-feedback-events.md) | Chargebacks as a ledger loss (ADMIN), chargeback.created + payment.outcome JSON events for the risk engine | Accepted |
| [0019](0019-distributed-tracing.md) | Distributed tracing: OpenTelemetry agent, W3C context through HTTP, gRPC, Kafka and the outbox; Jaeger | Accepted |
| [0020](0020-metrics-dashboards-json-logs.md) | Metrics for every service, billing business metrics, provisioned Grafana dashboard, JSON logs with trace ids | Accepted |
| [0021](0021-correctness-under-concurrency.md) | Correctness under concurrency: storm tests, row lock when recording, 409 for refused amounts, property tests | Accepted |
| [0022](0022-load-testing-with-k6.md) | k6 checkout load test: open model, fresh isolated stack, server-side fallback counter next to latencies | Accepted |
