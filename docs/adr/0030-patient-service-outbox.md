# ADR 0030 — patient-service: transactional outbox for patient events; HTTP status fixes

- Status: Accepted
- Date: 2026-11-01
- Same pattern as billing's outbox (ADR 0008). Closes PLAN weaknesses #4 (dual write), #6 (status codes), #10 (debug logging).

## Context
`createPatient` saved the patient, called billing over gRPC, then `kafkaTemplate.send()` with no transaction around
any of it. If Kafka was down the producer logged the error and the PATIENT_CREATED event was gone: analytics never
learned about the patient. billing has had a transactional outbox since Day 6a; patient-service was the one dual write left.

## Decision
- **Outbox table** `patient_event_outbox` (V5): the patient row and its event row are inserted in ONE transaction
  (`PatientEventOutbox.patientCreated`, propagation MANDATORY). The payload is the same protobuf `PatientEvent` as before.
- **`OutboxRelay`** every 500 ms: claims up to 100 unpublished rows oldest first (`FOR UPDATE SKIP LOCKED`, so several
  instances share the work), sends each to topic `patient` with key = patient id, and marks it published only after
  Kafka's ack (`send().get()`, 5 s). On failure: `attempts` + `last_error`, and the batch stops (order kept). Published
  rows are deleted after 7 days. Gauge `patient_outbox_pending`.
- The gRPC call to billing moved OUT of the transaction (no DB connection held during a network call); it still has
  its circuit breaker + fallback. A failed billing call doesn't undo the patient (unchanged behaviour).
- Simpler than billing's relay on purpose: one event type (PATIENT_CREATED, one per patient), so no per-aggregate
  ordering lock (billing uses an advisory lock, ADR 0008) and no trace context.
- **HTTP**: unknown patient 404 (was 400), duplicate email 409 (was 400), create 201 + `Location` (was 200).
  `show-sql` and cache TRACE logging off by default (env vars turn them on).
- Tests run on a real Postgres + Kafka + Redis (Testcontainers) instead of H2: the migrations are Postgres SQL.

## Consequences
- At-least-once: a crash between Kafka's ack and the commit sends the event again; analytics upserts patients by id,
  so a repeat is harmless.
- An event waits up to ~0.5 s before it is on Kafka (was: sent at once, or lost).
- The message now has a key (patient id); the value is unchanged, so analytic-service needs no change.
- Patient UPDATE / DELETE still publish nothing (they never did).
- Tests: `PatientOutboxIntegrationTest` (3: event written with the patient + published keyed by id; a refused patient
  leaves no event; Kafka paused -> row kept with attempts + error, published when Kafka is back) - with a fire-and-
  forget relay the last one fails. A first version of that test missed it: a cold producer throws at once while
  Kafka is paused, so the test now warms the producer first. `PatientControllerTest` 201 + Location, 409, 404.
