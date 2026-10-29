-- A card payment's lifecycle. The allowed status moves live in Java (PaymentStateMachine);
-- the CHECKs below make sure the amounts can never contradict the status, whoever writes the row.
CREATE TABLE payment (
    id                    UUID         PRIMARY KEY,
    billing_account_id    UUID         NOT NULL REFERENCES billing_account (id),
    -- the amount asked for and authorized; a partial capture takes less than this
    amount_minor          BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency              VARCHAR(3)   NOT NULL,
    captured_amount_minor BIGINT       NOT NULL DEFAULT 0,
    refunded_amount_minor BIGINT       NOT NULL DEFAULT 0,
    status                VARCHAR(20)  NOT NULL CHECK (status IN (
                              'CREATED', 'PENDING_REVIEW', 'AUTHORIZED', 'CAPTURED', 'PARTIALLY_REFUNDED',
                              'REFUNDED', 'FAILED', 'VOIDED', 'EXPIRED')),
    failure_reason        VARCHAR(255),
    -- optimistic lock: every UPDATE does "... WHERE id = ? AND version = ?" and bumps it
    version               BIGINT       NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT payment_captured_within_authorized
        CHECK (captured_amount_minor BETWEEN 0 AND amount_minor),
    CONSTRAINT payment_refunded_within_captured
        CHECK (refunded_amount_minor BETWEEN 0 AND captured_amount_minor),
    -- money was captured exactly when the status says so
    CONSTRAINT payment_captured_matches_status
        CHECK ((captured_amount_minor > 0) = (status IN ('CAPTURED', 'PARTIALLY_REFUNDED', 'REFUNDED'))),
    CONSTRAINT payment_refunded_matches_status
        CHECK ((refunded_amount_minor > 0) = (status IN ('PARTIALLY_REFUNDED', 'REFUNDED'))),
    CONSTRAINT payment_fully_refunded_matches_status
        CHECK ((status = 'REFUNDED') = (refunded_amount_minor > 0 AND refunded_amount_minor = captured_amount_minor)),
    CONSTRAINT payment_failure_reason_only_when_failed
        CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL))
);

CREATE INDEX payment_billing_account_idx ON payment (billing_account_id);
