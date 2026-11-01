# ADR 0028 — Cap concurrent risk engine calls (the thundering herd)

- Status: Accepted
- Date: 2026-11-01
- Amends: ADR 0017 (budget, retry, breaker). Evidence: `docs/CHAOS.md` finding "thundering herd after an outage".

## Context
When Postgres came back after the Day 23 outage, every client's queued retry arrived within a second: 99 risk checks
timed out in 1 s, the breaker opened, and the next ~30 s of payments were decided by billing's fallback rules - long
after the herd itself was over. The engine is one CPU per worker: its latency grows with the work queued at it, so
sending it everything at once makes EVERY call miss the 300 ms budget instead of some.

## Decision
A semaphore in `RiskAssessor`: at most `billing.risk.max-concurrent` (default 16) risk calls per billing instance in
flight. A payment that finds no free slot is decided by the fallback AT ONCE (reason `busy`, never waits - the budget
is for the engine's answer, not a queue) and is not counted against the engine by the breaker. One slot covers the
whole check (first attempt + retry). Gauge `billing_risk_in_flight`.

16: the 4-worker engine (risk ADR 0011) held 80 checks/s at p99 140 ms, ~2-11 calls in flight; 16 leaves headroom
for one instance while keeping the engine's queue short. Several billing instances multiply it (16 each).

## Alternatives
- Wait for a slot (bulkhead with max-wait): the waiting eats the 300 ms budget, then times out anyway.
- Client jitter only: helps, but billing can't rely on every client doing it.
- A shorter breaker open time: still 100% fallback during it; the cap keeps the engine in use.

## Consequences
- **Measured 2026-11-01** (Postgres stopped 35 s): breaker never opened (Day 23: ~30 s open), 73 `busy` + 8 timeouts
  instead of 174 circuit_open + 102 timeouts.
- During a herd, payments over the cap get the fallback (a `busy` decision each), but the engine stays fast for the
  rest and the breaker stays closed, so the fallback ends with the herd instead of 30 s later.
- The cap is per instance, not global; the engine's capacity must be >= instances x 16 for it to protect the engine.
- Not re-measured live (the Day 23 chaos run). Test: `RiskHerdTest` - 60 payments at once against a fake engine
  whose latency grows 25 ms per call in flight: without a cap most time out and the breaker opens; with a cap of 8
  no call times out, >= 8 get the engine's answer, the breaker stays closed and the next payment uses the engine.
