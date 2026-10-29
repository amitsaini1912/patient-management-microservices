-- Double-entry ledger.
-- Every movement of money is a journal_entry with at least two entry_lines.
-- Per currency, sum(DEBIT lines) = sum(CREDIT lines). Balances are never stored as
-- the truth: they are always derived from the lines.

-- ---------------------------------------------------------------------------
-- Accounts
-- ---------------------------------------------------------------------------
CREATE TABLE ledger_account (
    id                 UUID        PRIMARY KEY,
    purpose            VARCHAR(30) NOT NULL CHECK (purpose IN (
                           'PATIENT_RECEIVABLE', 'INSURER_RECEIVABLE', 'HOSPITAL_REVENUE',
                           'REFUNDS', 'FX_GAIN_LOSS', 'SUSPENSE')),
    type               VARCHAR(20) NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    currency           VARCHAR(3)  NOT NULL,
    -- set only for per-patient accounts; system accounts (revenue, suspense, ...) have NULL
    billing_account_id UUID        REFERENCES billing_account (id),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- target of the composite FK from entry_line: a line's currency must be its account's currency
    CONSTRAINT ledger_account_id_currency_key UNIQUE (id, currency),
    -- one receivable per patient, one system account per purpose + currency.
    -- NULLS NOT DISTINCT (Postgres 15+): two NULL billing_account_ids count as equal.
    CONSTRAINT ledger_account_one_per_purpose UNIQUE NULLS NOT DISTINCT (purpose, currency, billing_account_id),
    CONSTRAINT ledger_account_receivable_has_owner
        CHECK ((purpose = 'PATIENT_RECEIVABLE') = (billing_account_id IS NOT NULL))
);

-- System accounts for INR (other currencies are added on Day 9, multi-currency).
INSERT INTO ledger_account (id, purpose, type, currency) VALUES
    (gen_random_uuid(), 'INSURER_RECEIVABLE', 'ASSET',     'INR'),
    (gen_random_uuid(), 'HOSPITAL_REVENUE',   'REVENUE',   'INR'),
    (gen_random_uuid(), 'REFUNDS',            'EXPENSE',   'INR'),
    (gen_random_uuid(), 'FX_GAIN_LOSS',       'REVENUE',   'INR'),
    (gen_random_uuid(), 'SUSPENSE',           'LIABILITY', 'INR');

-- Billing accounts created before this migration also get their receivable.
INSERT INTO ledger_account (id, purpose, type, currency, billing_account_id)
SELECT gen_random_uuid(), 'PATIENT_RECEIVABLE', 'ASSET', currency, id
FROM billing_account;

-- ---------------------------------------------------------------------------
-- Journal entries and their lines
-- ---------------------------------------------------------------------------
CREATE TABLE journal_entry (
    id          UUID         PRIMARY KEY,
    description VARCHAR(255) NOT NULL,
    -- a correction is a new entry that points at the one it cancels; UNIQUE = reversed at most once
    reversal_of UUID         UNIQUE REFERENCES journal_entry (id),
    posted_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE entry_line (
    id                UUID       PRIMARY KEY,
    journal_entry_id  UUID       NOT NULL REFERENCES journal_entry (id),
    line_no           INT        NOT NULL,
    ledger_account_id UUID       NOT NULL,
    direction         VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    -- always positive; the direction says which side it is on
    amount_minor      BIGINT     NOT NULL CHECK (amount_minor > 0),
    currency          VARCHAR(3) NOT NULL,

    CONSTRAINT entry_line_entry_line_no_key UNIQUE (journal_entry_id, line_no),
    CONSTRAINT entry_line_account_currency_fk
        FOREIGN KEY (ledger_account_id, currency) REFERENCES ledger_account (id, currency)
);

-- "balance of account X" reads every line of X
CREATE INDEX entry_line_ledger_account_idx ON entry_line (ledger_account_id);

-- ---------------------------------------------------------------------------
-- Rule 1: every entry balances. Checked at COMMIT (deferred), not per INSERT,
-- because the lines of one entry are inserted one by one and are only balanced
-- once all of them are in.
-- ---------------------------------------------------------------------------
CREATE FUNCTION ledger_check_entry_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_entry_id UUID;
    v_lines    INT;
    v_currency VARCHAR(3);
BEGIN
    IF TG_TABLE_NAME = 'journal_entry' THEN
        v_entry_id := NEW.id;
    ELSE
        v_entry_id := NEW.journal_entry_id;
    END IF;

    SELECT count(*) INTO v_lines FROM entry_line WHERE journal_entry_id = v_entry_id;
    IF v_lines < 2 THEN
        RAISE EXCEPTION 'journal entry % has % line(s); at least 2 are required', v_entry_id, v_lines
            USING ERRCODE = 'check_violation';
    END IF;

    -- sum() of BIGINT is NUMERIC in Postgres, so this cannot overflow
    SELECT currency INTO v_currency
    FROM entry_line
    WHERE journal_entry_id = v_entry_id
    GROUP BY currency
    HAVING sum(CASE direction WHEN 'DEBIT' THEN amount_minor ELSE -amount_minor END) <> 0
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'journal entry % is unbalanced in %: debits <> credits', v_entry_id, v_currency
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$;

-- On journal_entry too, so an entry with zero lines can't be committed.
CREATE CONSTRAINT TRIGGER journal_entry_balanced
    AFTER INSERT ON journal_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_check_entry_balanced();

CREATE CONSTRAINT TRIGGER entry_line_balanced
    AFTER INSERT ON entry_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_check_entry_balanced();

-- ---------------------------------------------------------------------------
-- Rule 2: append-only. Posted history is never changed; a mistake is fixed by
-- posting a reversal entry.
-- ---------------------------------------------------------------------------
CREATE FUNCTION ledger_reject_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed, post a reversal entry instead', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER journal_entry_append_only
    BEFORE UPDATE OR DELETE ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_change();

CREATE TRIGGER journal_entry_no_truncate
    BEFORE TRUNCATE ON journal_entry
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_change();

CREATE TRIGGER entry_line_append_only
    BEFORE UPDATE OR DELETE ON entry_line
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_change();

CREATE TRIGGER entry_line_no_truncate
    BEFORE TRUNCATE ON entry_line
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_change();
