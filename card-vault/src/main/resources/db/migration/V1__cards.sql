-- card-vault: the only database that holds card numbers, and only encrypted (ADR 0013).
CREATE TABLE card (
    -- "tok_" + 128 random bits; what everyone else stores instead of the PAN
    token          VARCHAR(40)  PRIMARY KEY,
    -- AES-256-GCM(PAN) incl. the 16-byte tag; the token is the associated data, so a ciphertext copied onto
    -- another row fails to decrypt
    pan_ciphertext BYTEA        NOT NULL,
    iv             BYTEA        NOT NULL CHECK (octet_length(iv) = 12),
    -- which key encrypted it; old versions stay readable after a rotation
    key_version    INT          NOT NULL CHECK (key_version > 0),
    -- HMAC-SHA256(fingerprint key, PAN): same card -> same value, without being able to reverse it
    fingerprint    CHAR(64)     NOT NULL,
    last4          CHAR(4)      NOT NULL,
    brand          VARCHAR(20)  NOT NULL,
    expiry_month   SMALLINT     NOT NULL CHECK (expiry_month BETWEEN 1 AND 12),
    expiry_year    SMALLINT     NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX card_fingerprint_idx ON card (fingerprint);
CREATE INDEX card_key_version_idx ON card (key_version);

-- Every detokenization (the PAN leaving the vault) is recorded: who, which card, when.
CREATE TABLE detokenize_audit (
    id          UUID         PRIMARY KEY,
    token       VARCHAR(40)  NOT NULL REFERENCES card (token),
    client_name VARCHAR(50)  NOT NULL,
    at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX detokenize_audit_token_idx ON detokenize_audit (token);
