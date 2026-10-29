-- Day 4b: refunds as their own records, and expiry of old authorizations.

-- One row per successful refund. payment.refunded_amount_minor is the running total;
-- the rows say when, how much and why for each one.
CREATE TABLE refund (
    id                  UUID         PRIMARY KEY,
    payment_id          UUID         NOT NULL REFERENCES payment (id),
    amount_minor        BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency            VARCHAR(3)   NOT NULL,
    -- the processor's id for this refund, needed for reconciliation (Day 8)
    processor_reference VARCHAR(64)  NOT NULL,
    reason              VARCHAR(255),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX refund_payment_idx ON refund (payment_id);

-- When the hold was placed. Expiry counts from here, not from created_at.
ALTER TABLE payment ADD COLUMN authorized_at TIMESTAMPTZ;

-- Payments authorized before this migration: authorization happened right after creation.
UPDATE payment SET authorized_at = created_at WHERE processor_reference IS NOT NULL;

-- Every status that comes after an approved authorization has an authorized_at.
ALTER TABLE payment ADD CONSTRAINT payment_authorized_at_matches_status
    CHECK ((authorized_at IS NOT NULL) =
           (status IN ('AUTHORIZED', 'CAPTURED', 'PARTIALLY_REFUNDED', 'REFUNDED', 'VOIDED', 'EXPIRED')));

-- The expiry job only ever looks at AUTHORIZED rows. A partial index holds just those,
-- so it stays small however many old payments pile up.
CREATE INDEX payment_authorized_expiry_idx ON payment (authorized_at) WHERE status = 'AUTHORIZED';
