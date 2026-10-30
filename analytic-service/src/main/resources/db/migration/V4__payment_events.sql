-- Day 6b: payment events from billing-service (topic payment.events) -> analytics.

-- Idempotent consumer: one row per event we have applied. Inserted in the SAME transaction as the
-- projection change, so "applied" and "remembered as applied" can't disagree. A redelivered event finds
-- its row and is skipped.
CREATE TABLE processed_event (
    event_id     UUID                     PRIMARY KEY,
    topic        VARCHAR(200)             NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Latest known state of each payment (a "projection" of the event stream).
CREATE TABLE payment_projection (
    payment_id            UUID                     PRIMARY KEY,
    billing_account_id    UUID                     NOT NULL,
    status                VARCHAR(30)              NOT NULL,
    currency              VARCHAR(3)               NOT NULL,
    amount_minor          BIGINT                   NOT NULL,
    captured_amount_minor BIGINT                   NOT NULL,
    refunded_amount_minor BIGINT                   NOT NULL,
    failure_reason        VARCHAR(255),
    last_event_type       VARCHAR(50)              NOT NULL,
    -- occurred_at of the event that set this state; an older event never overwrites a newer one
    last_event_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Running totals per UTC day. Counters: applying an event twice WOULD double count, which is why
-- processed_event exists.
CREATE TABLE payment_daily_summary (
    summary_date   DATE        NOT NULL,
    currency       VARCHAR(3)  NOT NULL,
    captured_count INT         NOT NULL DEFAULT 0,
    captured_minor BIGINT      NOT NULL DEFAULT 0,
    refund_count   INT         NOT NULL DEFAULT 0,
    refunded_minor BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (summary_date, currency)
);
