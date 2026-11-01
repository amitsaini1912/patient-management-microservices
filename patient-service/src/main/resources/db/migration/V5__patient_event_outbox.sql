-- Transactional outbox for patient events (weakness #4, ADR 0030). The event row is written in the SAME transaction
-- as the patient; OutboxRelay publishes it to Kafka afterwards. Before: save, then kafkaTemplate.send() - if Kafka
-- was down the event was logged and lost for good, and analytics never heard of the patient.
CREATE TABLE patient_event_outbox (
    id            UUID         PRIMARY KEY,
    patient_id    UUID         NOT NULL,
    event_type    VARCHAR(50)  NOT NULL,
    payload       BYTEA        NOT NULL,     -- the serialized protobuf PatientEvent, exactly what goes to Kafka
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,               -- null = not on Kafka yet
    attempts      INT          NOT NULL DEFAULT 0,
    last_error    VARCHAR(500)
);

-- the relay only ever looks at unpublished rows, oldest first
CREATE INDEX patient_event_outbox_pending_idx ON patient_event_outbox (created_at) WHERE published_at IS NULL;
