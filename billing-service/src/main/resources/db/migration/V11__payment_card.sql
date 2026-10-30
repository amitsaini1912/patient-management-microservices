-- Day 10: the card behind a payment, as billing is allowed to know it: a vault token and its metadata.
-- Never the card number (it only exists in card-vault, ADR 0013). Null for payments made before Day 10.
ALTER TABLE payment
    ADD COLUMN card_token       VARCHAR(40),
    ADD COLUMN card_last4       VARCHAR(4),
    ADD COLUMN card_brand       VARCHAR(20),
    -- same card -> same fingerprint (even with different tokens): "has this card paid before?" without the PAN
    ADD COLUMN card_fingerprint VARCHAR(64),
    ADD CONSTRAINT payment_card_all_or_nothing CHECK (
        (card_token IS NULL) = (card_last4 IS NULL) AND (card_token IS NULL) = (card_fingerprint IS NULL));
CREATE INDEX payment_card_fingerprint_idx ON payment (card_fingerprint) WHERE card_fingerprint IS NOT NULL;
