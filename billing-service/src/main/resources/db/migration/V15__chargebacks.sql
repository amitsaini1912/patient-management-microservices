-- Day 19b (ADR 0018): chargebacks. The cardholder's bank took (part of) a captured payment back through the card
-- network; the acquirer debits it from what it owes us. Recorded by ops (ADMIN) from the acquirer's dispute notice.

-- 1) running total on the payment: refundable = captured - refunded - charged back
ALTER TABLE payment
    ADD COLUMN charged_back_amount_minor BIGINT NOT NULL DEFAULT 0 CHECK (charged_back_amount_minor >= 0),
    ADD CONSTRAINT payment_refunded_and_charged_back_within_captured
        CHECK (refunded_amount_minor + charged_back_amount_minor <= captured_amount_minor);

-- 2) one row per chargeback (a payment can be disputed more than once, e.g. partial amounts)
CREATE TABLE chargeback (
    id            UUID         PRIMARY KEY,           -- derived from the Idempotency-Key, like refunds
    payment_id    UUID         NOT NULL REFERENCES payment (id),
    amount_minor  BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency      VARCHAR(3)   NOT NULL,
    -- the category the risk engine gets in chargeback.created (^[a-z0-9_]+$, its contract)
    reason        VARCHAR(64)  NOT NULL CHECK (reason ~ '^[a-z0-9_]+$'),
    -- the acquirer's / network's case id, if ops has it
    case_reference VARCHAR(64),
    -- market rate (payment currency -> book currency) on the day: the loss is booked at it (1 for INR payments)
    fx_rate       NUMERIC(18, 8) NOT NULL CHECK (fx_rate > 0),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX chargeback_payment_idx ON chargeback (payment_id);

-- 3) where the loss goes: its own expense account (like REFUNDS), in book currency
ALTER TABLE ledger_account DROP CONSTRAINT ledger_account_purpose_check;
ALTER TABLE ledger_account ADD CONSTRAINT ledger_account_purpose_check CHECK (purpose IN (
    'PATIENT_RECEIVABLE', 'INSURER_RECEIVABLE', 'PROCESSOR_RECEIVABLE', 'HOSPITAL_REVENUE',
    'REFUNDS', 'CHARGEBACKS', 'FX_GAIN_LOSS', 'FX_CLEARING', 'SUSPENSE'));
INSERT INTO ledger_account (id, purpose, type, currency) VALUES (gen_random_uuid(), 'CHARGEBACKS', 'EXPENSE', 'INR');

-- 4) journal entries can come from a chargeback (source id = chargeback id). Settlement reconciliation (ADR 0011)
--    only matches CAPTURE / REFUND: chargebacks are on the acquirer's dispute report, not the settlement report.
ALTER TABLE journal_entry DROP CONSTRAINT journal_entry_source_type_check;
ALTER TABLE journal_entry ADD CONSTRAINT journal_entry_source_type_check
    CHECK (source_type IN ('CAPTURE', 'REFUND', 'CHARGEBACK'));
