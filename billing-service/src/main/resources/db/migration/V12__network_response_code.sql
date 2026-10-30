-- Day 11: what the card network answered for the authorization (ISO 8583 field 39: 00 approved, 05, 51, 54, 91 ...)
-- and whether a decline is worth retrying. Null code = before Day 11, or a processor that doesn't say.
ALTER TABLE payment
    ADD COLUMN network_response_code VARCHAR(2),
    ADD COLUMN retryable             BOOLEAN NOT NULL DEFAULT FALSE,
    -- only a decline can be retried: an approved payment is never "retryable"
    ADD CONSTRAINT payment_retryable_only_failed CHECK (NOT retryable OR status = 'FAILED');
