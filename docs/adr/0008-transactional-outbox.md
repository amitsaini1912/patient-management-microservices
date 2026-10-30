# ADR 0008 — Payment events via a transactional outbox; protobuf payload; one ordered relay

- Status: Accepted
- Date: 2026-10-30

## Context
Other services (analytics on Day 6b, the risk engine's feedback loop later) need to know when a payment is
authorized, captured, refunded, etc. "Save to Postgres, then publish to Kafka" is a dual write: if the process dies
or Kafka is down between the two, the change and the event disagree for good. patient-service has exactly this bug
today (its `KafkaProducer` logs and drops the event). We can't put Postgres and Kafka in one transaction.

## Decision
1. **Outbox table** `outbox_event`, written by `OutboxWriter` **inside the same DB transaction as the payment change**
   (`Propagation.MANDATORY`: calling it without a transaction throws). Every committed change has its event; every
   rolled-back change (including lost `@Version` retries) has none.
   Events: `payment.authorized | failed | captured | voided | expired | refunded` (not CREATED: internal state).
2. **Payload: protobuf** `payment.events.PaymentEvent` (`billing-service/src/main/proto/payment_event.proto`), same
   approach as the existing `PatientEvent`. Fields: `event_id`, `event_type`, `schema_version` (=1), `occurred_at`,
   payment id/amounts/status/currency, plus refund/failure details. Evolution rule: only add fields with new numbers.
   The outbox stores the serialized bytes (`BYTEA`), so what we publish is exactly what we committed.
3. **Topic** `payment.events`, 3 partitions, **record key = payment_id**: all events of one payment are on one
   partition, so Kafka keeps their order. `event_id` and `event_type` are also record headers.
4. **Relay** (`OutboxRelay`, `@Scheduled` every 0.5 s): in one transaction take
   `pg_try_advisory_xact_lock`, then `SELECT … WHERE status='PENDING' ORDER BY position LIMIT 100 FOR UPDATE SKIP LOCKED`,
   send rows **one at a time** (wait for `acks=all`), mark `SENT`. Loop while batches are full.
   - **Temporary failure** (any Kafka `RetriableException`, e.g. timeout): `attempts+1`, `last_error`, stop the batch
     (nothing may overtake it), pause the relay 1 s → 2 s → 4 s … max 60 s.
   - **Permanent failure** (e.g. `RecordTooLargeException`): row → `FAILED`, logged at ERROR, the batch continues.
     Without this one bad row would block all events forever. FAILED rows need a human (fix, then set back to PENDING).
5. **One relay at a time** (advisory lock), instead of the plan's "several relays sharing work with SKIP LOCKED".
   With parallel relays, instance B can publish "captured" before instance A publishes "authorized" of the same
   payment. Ordering per payment matters more to consumers than relay throughput.
6. **Producer settings**: `acks=all`, `enable.idempotence=true`, short timeouts (`max.block.ms=3000`,
   `delivery.timeout.ms=6000`). The outbox row is the retry mechanism, so the producer doesn't need to wait 2 minutes,
   and the relay's DB transaction stays short.
7. **Delivery guarantee: at least once.** Duplicates are possible (crash after Kafka ack, before our commit; or a
   timed-out send the broker still writes). Consumers must dedup on `event_id` (Day 6b).
8. SENT rows are deleted after 7 days (hourly job).

## Alternatives considered
| Option | Why not |
|---|---|
| Publish to Kafka right after commit | Dual write: crash or Kafka down after commit = event lost (patient-service's bug). |
| Publish inside the DB transaction, before commit | Event out, then the transaction rolls back = event for a change that never happened. |
| Kafka transactions / XA with the DB | Kafka can't join a Postgres transaction; XA is slow, fragile, unsupported by most setups. |
| Debezium / CDC reading the WAL | The best production answer (no polling, no relay code), but needs Kafka Connect + Debezium + replication slot ops. Too much infra for this project. Our columns (id, aggregate type/id, event type, payload) map onto Debezium's outbox event router, so switching later doesn't change the writers. |
| JSON + `schema_version` | Easier for the Python risk engine and readable in SQL. You chose protobuf to match `PatientEvent` (decision D5). |
| Parallel relays with SKIP LOCKED only | Breaks per-payment order across instances (see 5). |
| Per-row backoff (`next_attempt_at`) | A later event of the same payment could be sent while an earlier one waits = reordering. Relay-level pause keeps order. |
| Async batch send + flush | Faster, but after a partial failure the rows that did go out may be ahead of one that didn't. One-at-a-time is simple and enough here (~hundreds/s). |

## Consequences
- + No lost and no phantom events: tested (commit → event arrives; rollback → no row; 6 concurrent refunds with
  `@Version` retries → exactly 6 events; Kafka paused → payments still work, row stays PENDING with attempts and
  error, sent automatically after Kafka returns).
- + Order per payment kept (single relay, key = payment_id, stop on first failure).
- − At-least-once: **observed in the Kafka-pause test**: the event arrived twice (the paused broker wrote the
  timed-out first send on unpause, then our retry). Consumers must be idempotent.
- − Latency: up to the poll interval (0.5 s) + backoff during outages.
- − Throughput limited to one relay sending one record at a time. Fine now; CDC or per-partition relays if needed.
- − One more table to watch: pending count / oldest pending age = "outbox lag" (dashboard on Day 20b).
- − Protobuf: rows in `outbox_event.payload` aren't readable with plain SQL; the .proto must be copied to consumers
  (as with `patient_event.proto`) until there's a shared schema module or registry.
