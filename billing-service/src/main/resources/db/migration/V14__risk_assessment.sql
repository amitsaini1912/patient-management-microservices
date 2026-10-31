-- Day 19 (ADR 0017): the risk engine's verdict on each payment, and the manual review of held ones.
-- Checked BEFORE authorization: HOLD -> PENDING_REVIEW (nothing sent to the card network), BLOCK -> FAILED.

ALTER TABLE payment
    -- what billing sent as context (kept so a retried authorize sends the SAME facts: the engine answers 409 to
    -- the same transaction id with different facts)
    ADD COLUMN risk_client_ip      VARCHAR(45),
    ADD COLUMN risk_device_id      VARCHAR(128),
    ADD COLUMN risk_decision       VARCHAR(10) CHECK (risk_decision IN ('ALLOW', 'HOLD', 'BLOCK')),
    -- ENGINE = the risk engine answered; FALLBACK = billing's own rules (engine off, down, slow, circuit open)
    ADD COLUMN risk_source         VARCHAR(10) CHECK (risk_source IN ('ENGINE', 'FALLBACK')),
    ADD COLUMN risk_score          DOUBLE PRECISION,
    ADD COLUMN risk_reason_codes   VARCHAR(300),
    ADD COLUMN risk_model_version  VARCHAR(64),
    ADD COLUMN risk_rules_version  VARCHAR(64),
    ADD COLUMN review_decision     VARCHAR(10) CHECK (review_decision IN ('APPROVED', 'REJECTED')),
    ADD COLUMN reviewed_by         VARCHAR(100),
    ADD COLUMN reviewed_at         TIMESTAMPTZ,
    ADD COLUMN review_note         VARCHAR(500),
    ADD CONSTRAINT payment_risk_complete CHECK ((risk_decision IS NULL) = (risk_source IS NULL)),
    ADD CONSTRAINT payment_review_complete CHECK ((review_decision IS NULL) = (reviewed_at IS NULL));

-- the review queue: held payments, oldest first
CREATE INDEX payment_pending_review_idx ON payment (created_at) WHERE status = 'PENDING_REVIEW';
