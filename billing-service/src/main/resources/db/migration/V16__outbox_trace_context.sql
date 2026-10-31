-- Day 20a (ADR 0019): the W3C trace context of the request that wrote the event. The relay publishes later, on
-- another thread; with this it continues the request's trace instead of starting a new one, so one trace id goes
-- from the HTTP request through Kafka to every consumer. NULL when tracing is off.
ALTER TABLE outbox_event
    ADD COLUMN trace_parent VARCHAR(55)  CHECK (trace_parent ~ '^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$'),
    ADD COLUMN trace_state  VARCHAR(512);
