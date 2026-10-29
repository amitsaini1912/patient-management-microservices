-- Day 4a: payments go through a processor (acquirer).

-- The processor's id for the authorization; needed to capture/void it and, later, to reconcile.
ALTER TABLE payment ADD COLUMN processor_reference VARCHAR(64);

-- Captured card money is collected by the acquirer and paid out to us later (settlement, Day 7/8).
-- Until then the acquirer owes it to us: an asset.
ALTER TABLE ledger_account DROP CONSTRAINT ledger_account_purpose_check;
ALTER TABLE ledger_account ADD CONSTRAINT ledger_account_purpose_check CHECK (purpose IN (
    'PATIENT_RECEIVABLE', 'INSURER_RECEIVABLE', 'PROCESSOR_RECEIVABLE', 'HOSPITAL_REVENUE',
    'REFUNDS', 'FX_GAIN_LOSS', 'SUSPENSE'));

INSERT INTO ledger_account (id, purpose, type, currency) VALUES
    (gen_random_uuid(), 'PROCESSOR_RECEIVABLE', 'ASSET', 'INR');
