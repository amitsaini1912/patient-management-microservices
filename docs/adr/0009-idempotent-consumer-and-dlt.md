# ADR 0009 — analytic-service: idempotent payment-event consumer + dead-letter topics

- Status: Accepted
- Date: 2026-10-30

## Context
billing-service publishes payment events at least once (ADR 0008). We saw a real duplicate in its Kafka-pause test.
Kafka also redelivers any record whose offset wasn't committed (consumer crash, rebalance). analytic-service must
therefore survive duplicates. Separately, both of its listeners had no failure policy: the existing `patient`
listener logged unreadable messages and dropped them, and with Spring's default handler a failing record is retried a
few times and then only logged, so it's lost too.

## Decision
1. **Idempotent consumer**: `processed_event(event_id PK)`. In ONE transaction:
   `INSERT … ON CONFLICT (event_id) DO NOTHING`; 0 rows → duplicate → return; 1 row → apply the event. The marker
   and the effect commit together, so a crash before the commit means neither happened (redelivery applies it once);
   after the commit, the redelivery is skipped.
2. **Read models**: `payment_projection` (latest state per payment) and `payment_daily_summary` (UTC-day counters of
   captures/refunds). The counters are why dedup is required: replaying a state upsert is harmless, replaying `+1` is not.
3. **Ordering guard**: the projection only moves forward
   (`ON CONFLICT … DO UPDATE … WHERE last_event_at <= EXCLUDED.last_event_at`). A late older event is still marked
   processed and still counted in the daily totals (it's a real fact); it just can't move the status backwards.
   `occurred_at` comes from one billing clock per event; good enough because billing publishes in order per payment.
4. **Errors** (one `DefaultErrorHandler` bean for every listener):
   - `InvalidEventException` (bad protobuf, empty/invalid ids, unknown schema_version): **no retry**, straight to
     `<topic>.DLT` (`payment.events.DLT`, `patient.DLT`).
   - anything else (e.g. DB down): retried 2× at 1 s, then to the DLT.
   - The DLT record keeps the original key and bytes plus Spring's `kafka_dlt-*` headers (original topic, partition,
     offset, exception class and message). DLT partition = -1 (let Kafka choose), so DLTs can have 1 partition.
   - The `patient` listener now throws instead of logging and dropping (fixes weakness #5).
5. **Unknown event types are accepted** (state only, no counters): billing can add a type without breaking us.
6. **Config**: DB URL/user/password from env (`ANALYTICS_DB_*`, fixes weakness #9); group id and topics in
   properties; `auto-offset-reset=earliest`, so a brand-new group doesn't skip events published before its first start.
7. **Tests** run on Testcontainers Postgres + Kafka. The H2 `contextLoads` test stays, with listeners and topic
   creation off. Test overrides moved to `src/test/resources/config/application.properties`: the old
   `src/test/resources/application.properties` silently *replaced* the main file in tests.

## Alternatives considered
| Option | Why not |
|---|---|
| Make every write naturally idempotent (no processed table) | Works for the state upsert, not for counters/sums, which are the point of analytics. |
| Dedup in memory / Redis with TTL | Lost on restart, or a second system that can disagree with the DB write. Same-transaction table is exact. |
| Kafka exactly-once (transactions, `read_committed`) | Only covers Kafka→Kafka. Our side effect is a Postgres write, so we'd still need dedup. |
| Non-blocking retry topics (`@RetryableTopic`) | Doesn't block the partition while retrying, but breaks per-payment order. For analytics, order matters more; we keep short blocking retries. |
| Default handler (log and skip after retries) | The message is gone; nobody can look at it or replay it. |
| Retry poison messages too | They never succeed; they just hold up the partition. |

## Consequences
- + Duplicates are harmless (tested: same event twice → counters +1, one `processed_event` row).
- + A bad message is parked with full context, and the partition keeps flowing (tested for both topics, and by hand
  against the compose stack).
- − `processed_event` grows forever: one row per event. Needs a retention job once it matters (keep longer than
  Kafka's retention + billing's outbox retention, i.e. well over 7 days).
- − Nothing reads the DLTs yet: no alert, no replay tool. Day 20b metrics + a runbook.
- − Blocking retries: a DB outage stalls the partition for ~2 s per record before parking it in the DLT. If the DB is
  down for long, *good* messages end up in the DLT and must be replayed. Acceptable for analytics; revisit with a
  circuit breaker / pause-the-container approach if it matters.
- − The `.proto` is copied from billing; drift is caught only by review (shared schema module or registry later).
