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
