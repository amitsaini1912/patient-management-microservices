-- Day 7: webhooks from the acquirer (payment-network-simulator). One row per event id: the acquirer retries and
-- sometimes sends an event twice; the PRIMARY KEY makes the second one a no-op (ADR 0010).
CREATE TABLE webhook_event (
    -- the acquirer's event id (evt_...)
    id          VARCHAR(255) PRIMARY KEY,
    type        VARCHAR(100) NOT NULL,
    -- merchant_reference from the event = our payment id; null if it wasn't a UUID
    payment_id  UUID,
    -- what we did with it: APPLIED (moved the payment forward) or IGNORED_* (stale, unknown, mismatch) or RECORDED
    outcome     VARCHAR(40)  NOT NULL,
    -- the exact body we verified, for audits and replays
    payload     TEXT         NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX webhook_event_payment_idx ON webhook_event (payment_id);
