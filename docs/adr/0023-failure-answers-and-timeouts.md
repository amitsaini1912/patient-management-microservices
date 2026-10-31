# ADR 0023 — What clients are told when a dependency fails; gateway timeouts

- Status: Accepted
- Date: 2026-10-31
- Evidence: `docs/CHAOS.md` (Toxiproxy tests + live chaos under k6 load).

## Context
Day 23 broke each dependency under load. The system never lost or double-moved money, but clients were often told
the wrong thing: a database outage was a 500, an acquirer timeout was a 502 "refused" (clients gave up on 102
payments that were then approved), and the gateway had no timeouts (a hung card-vault held clients for 60 s, then 500).

## Decision
One rule: **temporary = 503 + Retry-After, and the same Idempotency-Key is safe to retry; 500/502 only for real errors.**

| Situation | Answer |
|---|---|
| billing's database unreachable / connection died (any layer: filter, controller, rollback, gRPC) | 503 + Retry-After: 1 (gRPC: UNAVAILABLE); idempotency key released |
| acquirer did not answer (timeout, connection refused): outcome unknown | 503 + Retry-After: 1, "retry with the same Idempotency-Key" |
| acquirer answered with an error / disagrees about state | 502 (unchanged) |
| acquirer refused the amount | 409 (Day 21, unchanged) |
| gateway can't connect to a service (2 s) | 503 + Retry-After: 1 |
| gateway connected, no answer (10 s; tokenize 3 s) | 504 |

- "Database unavailable" is decided **by cause** (`DatabaseErrors`), because the error Spring throws is often the
  failed rollback, not the original I/O error.
- A dead pooled connection after a Postgres restart costs one 503 per connection; no validation on every borrow.
- Gateway timeouts must stay above billing's slowest legitimate request (risk 0.3 s + vault 2 s + acquirer 5 s).

## Consequences
- Clients must retry 503 with the same key (and 409 + Retry-After while the key is still in progress), with backoff
  and **jitter**: after an outage, queued retries arrive together (seen: 99 risk timeouts in one second).
- An acquirer outage longer than the client's patience still leaves authorizations nobody captures (59 in a 60 s
  freeze; were 102). Next step: billing resolves stale CREATED payments itself (status inquiry by key, void if
  abandoned). Until then the 7-day authorization expiry releases them.
- If billing's database stays down longer than the lock timeout (60 s), a key whose release failed frees itself then.
  Retries before that get 409 + Retry-After.
