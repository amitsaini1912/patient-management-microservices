-- Day 6a: transactional outbox. A row is written in the SAME transaction as the payment change it describes,
-- so "payment changed" and "event will be published" commit or roll back together. A relay publishes the rows
-- to Kafka afterwards (ADR 0008).
CREATE TABLE outbox_event (
    -- = event_id inside the message; consumers dedup on it
    id              UUID         PRIMARY KEY,
    -- the order rows were written in; the relay publishes in this order
    position        BIGINT       GENERATED ALWAYS AS IDENTITY UNIQUE,
    aggregate_type  VARCHAR(50)  NOT NULL,
    -- no FK to payment: the outbox row is inserted (JDBC) before Hibernate flushes a new payment row
    aggregate_id    UUID         NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    topic           VARCHAR(200) NOT NULL,
    message_key     VARCHAR(200) NOT NULL,
    -- the serialized protobuf message
    payload         BYTEA        NOT NULL,
    -- PENDING -> SENT, or FAILED when Kafka rejects it for good (e.g. too large); FAILED needs a human
    status          VARCHAR(10)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts        INT          NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error      VARCHAR(1000),
    created_at      TIMESTAMPTZ  NOT NULL,
    sent_at         TIMESTAMPTZ,
    CONSTRAINT outbox_event_sent_at_when_sent CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
);

-- the relay's query: pending rows in write order. Partial, so it only holds the (few) unsent rows.
CREATE INDEX outbox_event_pending_idx ON outbox_event (position) WHERE status = 'PENDING';
-- retention cleanup of sent rows
CREATE INDEX outbox_event_sent_at_idx ON outbox_event (sent_at) WHERE status = 'SENT';
