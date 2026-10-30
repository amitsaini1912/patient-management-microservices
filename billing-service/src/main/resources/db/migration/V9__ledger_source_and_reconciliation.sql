-- Day 8: reconciliation against the acquirer's daily settlement report (ADR 0011).

-- 1) What a journal entry records, so it can be matched to the acquirer's rows:
--      CAPTURE -> source_id = payment id   (acquirer row: merchant_reference)
--      REFUND  -> source_id = refund id    (acquirer row: its refund id = refund.processor_reference)
--    NULL for manual entries and reversals. Not backfilled: entries posted before V9 only say it in their
--    description, and changing them would mean switching off the append-only trigger (dev data only).
ALTER TABLE journal_entry
    ADD COLUMN source_type VARCHAR(20) CHECK (source_type IN ('CAPTURE', 'REFUND')),
    ADD COLUMN source_id   UUID,
    ADD CONSTRAINT journal_entry_source_complete CHECK ((source_type IS NULL) = (source_id IS NULL));

-- a capture or refund is posted at most once: a second posting (bug, double webhook) fails at INSERT
CREATE UNIQUE INDEX journal_entry_source_key ON journal_entry (source_type, source_id) WHERE source_type IS NOT NULL;
-- reconciliation reads entries by day
CREATE INDEX journal_entry_posted_at_idx ON journal_entry (posted_at) WHERE source_type IS NOT NULL;

-- 2) One row per reconciliation run; a date can be re-run (the latest run counts).
CREATE TABLE recon_run (
    id               UUID         PRIMARY KEY,
    run_date         DATE         NOT NULL,   -- the UTC business day [00:00Z, 24:00Z) being checked
    status           VARCHAR(10)  NOT NULL CHECK (status IN ('COMPLETED', 'FAILED')),
    ledger_rows      INT          NOT NULL DEFAULT 0,
    settlement_rows  INT          NOT NULL DEFAULT 0,
    matched          INT          NOT NULL DEFAULT 0,
    mismatched       INT          NOT NULL DEFAULT 0,
    error            VARCHAR(1000),
    started_at       TIMESTAMPTZ  NOT NULL,
    finished_at      TIMESTAMPTZ  NOT NULL
);
CREATE INDEX recon_run_date_idx ON recon_run (run_date, started_at DESC);

-- 3) Only the problems are stored; matched rows are just counted.
CREATE TABLE recon_item (
    id                        UUID         PRIMARY KEY,
    run_id                    UUID         NOT NULL REFERENCES recon_run (id),
    classification            VARCHAR(30)  NOT NULL CHECK (classification IN
                                  ('MISSING_IN_LEDGER', 'MISSING_IN_SETTLEMENT', 'AMOUNT_MISMATCH', 'CURRENCY_MISMATCH')),
    transaction_type          VARCHAR(10)  NOT NULL CHECK (transaction_type IN ('CAPTURE', 'REFUND')),
    -- the match key: payment id for captures, the acquirer's refund id for refunds
    reference                 VARCHAR(255) NOT NULL,
    ledger_amount_minor       BIGINT,
    ledger_currency           VARCHAR(3),
    settlement_amount_minor   BIGINT,
    settlement_currency       VARCHAR(3),
    settlement_transaction_id VARCHAR(255)
);
CREATE INDEX recon_item_run_idx ON recon_item (run_id);
