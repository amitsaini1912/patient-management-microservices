-- Day 20b (ADR 0020): the billing_outbox_failed gauge counts FAILED rows on every Prometheus scrape (15 s). FAILED is
-- rare, SENT rows stay 7 days: a partial index keeps that count from scanning the whole table.
CREATE INDEX outbox_event_failed_idx ON outbox_event (created_at) WHERE status = 'FAILED';
