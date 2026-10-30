-- Day 13: tamper-evident audit log (ADR 0016). One hash chain over every audited change:
--   hash(n) = HMAC-SHA256(key, prev_hash || row n's fields)      prev_hash(n) = hash(n-1), prev_hash(1) = 64 zeros
-- Changing, deleting or inserting any row breaks the chain from that row on; GET /audit/verify finds the first break.
-- The key is NOT in the database, so someone who can write to the DB still can't recompute a valid chain.

CREATE TABLE audit_log (
    -- 1, 2, 3 ... without gaps. Assigned under the chain-head lock, not from a SEQUENCE: a sequence skips numbers
    -- when a transaction rolls back, and a gap must mean "a row was deleted".
    seq          BIGINT       PRIMARY KEY CHECK (seq > 0),
    occurred_at  TIMESTAMPTZ  NOT NULL,
    actor        VARCHAR(100) NOT NULL,   -- user:<X-User-Id> | acquirer | system | internal
    action       VARCHAR(50)  NOT NULL,   -- payment.captured, fx.rate_recorded ...
    entity_type  VARCHAR(30)  NOT NULL,
    entity_id    VARCHAR(64)  NOT NULL,
    -- canonical JSON (sorted keys). TEXT, not JSONB: JSONB re-formats, and we must re-hash the exact stored bytes.
    details      TEXT         NOT NULL,
    prev_hash    VARCHAR(64)  NOT NULL,
    hash         VARCHAR(64)  NOT NULL
);
CREATE INDEX audit_log_entity_idx ON audit_log (entity_type, entity_id, seq);

-- The end of the chain. Exactly one row; every append locks it (FOR UPDATE), so appends happen one at a time and
-- two writers can never both build on the same prev_hash (a fork). It also lets verify notice a cut-off tail.
CREATE TABLE audit_chain_head (
    id        SMALLINT    PRIMARY KEY CHECK (id = 1),
    last_seq  BIGINT      NOT NULL,
    last_hash VARCHAR(64) NOT NULL
);
INSERT INTO audit_chain_head (id, last_seq, last_hash) VALUES (1, 0, repeat('0', 64));

-- Append-only, like the ledger (V2): the application can only INSERT. Whoever can switch this trigger off
-- (table owner, superuser) is caught by the chain instead.
CREATE FUNCTION audit_reject_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_reject_change();

CREATE TRIGGER audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit_reject_change();

-- the head may move forward (UPDATE) but never disappear
CREATE TRIGGER audit_chain_head_no_delete
    BEFORE DELETE ON audit_chain_head
    FOR EACH ROW EXECUTE FUNCTION audit_reject_change();

CREATE TRIGGER audit_chain_head_no_truncate
    BEFORE TRUNCATE ON audit_chain_head
    FOR EACH STATEMENT EXECUTE FUNCTION audit_reject_change();
