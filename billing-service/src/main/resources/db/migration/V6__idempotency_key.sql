-- Day 5: Idempotency-Key for every money-moving POST.
-- One row per (scope, key). The PRIMARY KEY is the lock: the first request that INSERTs the row owns
-- the key; every other request with the same key finds the row and replays, waits, or is rejected.
CREATE TABLE idempotency_key (
    -- who the key belongs to; keys only need to be unique within a scope (see ADR 0007)
    scope                 VARCHAR(100) NOT NULL,
    idempotency_key       VARCHAR(255) NOT NULL,
    -- hex SHA-256 of method + path + body: same key with a different request is a client bug -> 422
    request_hash          CHAR(64)     NOT NULL,
    status                VARCHAR(12)  NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    -- the stored response, replayed byte for byte
    response_status       INT,
    response_content_type VARCHAR(100),
    response_location     VARCHAR(500),
    response_body         BYTEA,
    -- when the current owner started; an IN_PROGRESS row older than the lock timeout was
    -- abandoned (crash) and may be taken over by a retry
    locked_at             TIMESTAMPTZ  NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at            TIMESTAMPTZ  NOT NULL,

    PRIMARY KEY (scope, idempotency_key),
    CONSTRAINT idempotency_key_response_when_completed
        CHECK ((status = 'COMPLETED') = (response_status IS NOT NULL))
);

-- the cleanup job deletes by expiry
CREATE INDEX idempotency_key_expires_idx ON idempotency_key (expires_at);
