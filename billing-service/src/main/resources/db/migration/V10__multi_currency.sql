-- Day 9: multi-currency (INR book currency; patients may pay by card in USD or EUR). ADR 0012.

-- 1) Exchange rates. rate = units of quote per 1 unit of base (USD->INR 83.25). NUMERIC: exact, never float.
CREATE TABLE fx_rate (
    id             UUID           PRIMARY KEY,
    base_currency  VARCHAR(3)     NOT NULL,
    quote_currency VARCHAR(3)     NOT NULL,
    rate           NUMERIC(18, 8) NOT NULL CHECK (rate > 0),
    source         VARCHAR(100)   NOT NULL,   -- where it came from (seed, manual, a provider name)
    as_of          TIMESTAMPTZ    NOT NULL,   -- when the rate is valid from
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CHECK (base_currency <> quote_currency)
);
-- "latest rate for USD->INR at time T"
CREATE INDEX fx_rate_lookup_idx ON fx_rate (base_currency, quote_currency, as_of DESC, created_at DESC);

INSERT INTO fx_rate (id, base_currency, quote_currency, rate, source, as_of) VALUES
    (gen_random_uuid(), 'USD', 'INR', 83.25000000, 'seed', '2026-01-01T00:00:00Z'),
    (gen_random_uuid(), 'EUR', 'INR', 90.10000000, 'seed', '2026-01-01T00:00:00Z');

-- 2) The rate is LOCKED on the payment at authorization; capture and the patient side of refunds use it.
--    book_currency = the billing account's currency (INR). Same-currency payments: rate 1, no fx_rate row.
ALTER TABLE payment
    ADD COLUMN book_currency VARCHAR(3),
    ADD COLUMN fx_rate       NUMERIC(18, 8),
    ADD COLUMN fx_rate_id    UUID REFERENCES fx_rate (id);
UPDATE payment SET book_currency = currency, fx_rate = 1;
ALTER TABLE payment
    ALTER COLUMN book_currency SET NOT NULL,
    ALTER COLUMN fx_rate SET NOT NULL,
    ADD CONSTRAINT payment_fx_rate_positive CHECK (fx_rate > 0),
    ADD CONSTRAINT payment_same_currency_rate_is_one CHECK (currency <> book_currency OR fx_rate = 1);

-- the market rate on the day of the refund (the rate the foreign money left our position at), for audit
ALTER TABLE refund ADD COLUMN fx_rate NUMERIC(18, 8);
UPDATE refund SET fx_rate = 1;
ALTER TABLE refund ALTER COLUMN fx_rate SET NOT NULL;

-- 3) FX_CLEARING: the currency "trading" account that links the legs of a conversion. One per currency.
--    Its USD balance = the USD position we hold; its INR balance = what that position is carried at in INR.
ALTER TABLE ledger_account DROP CONSTRAINT ledger_account_purpose_check;
ALTER TABLE ledger_account ADD CONSTRAINT ledger_account_purpose_check CHECK (purpose IN (
    'PATIENT_RECEIVABLE', 'INSURER_RECEIVABLE', 'PROCESSOR_RECEIVABLE', 'HOSPITAL_REVENUE',
    'REFUNDS', 'FX_GAIN_LOSS', 'FX_CLEARING', 'SUSPENSE'));

INSERT INTO ledger_account (id, purpose, type, currency) VALUES
    (gen_random_uuid(), 'FX_CLEARING',          'EQUITY', 'INR'),
    (gen_random_uuid(), 'FX_CLEARING',          'EQUITY', 'USD'),
    (gen_random_uuid(), 'FX_CLEARING',          'EQUITY', 'EUR'),
    -- the acquirer owes us in the currency the card was charged in
    (gen_random_uuid(), 'PROCESSOR_RECEIVABLE', 'ASSET',  'USD'),
    (gen_random_uuid(), 'PROCESSOR_RECEIVABLE', 'ASSET',  'EUR');
