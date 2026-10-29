# Architecture Decision Records

One file per important decision: what we chose, what else we considered, and what it costs us.
Format: Context → Decision → Alternatives → Consequences. ADRs are never edited after acceptance;
a new ADR supersedes an old one.

| # | Decision | Status |
|---|---|---|
| [0001](0001-money-in-minor-units.md) | Store money as `long` minor units, BigDecimal only for FX | Accepted |
| [0002](0002-billing-on-spring-boot-3-4.md) | billing-service on Spring Boot 3.4.1 like the other services | Accepted |
| [0003](0003-double-entry-ledger.md) | Double-entry, append-only ledger enforced in code and in Postgres | Accepted |
