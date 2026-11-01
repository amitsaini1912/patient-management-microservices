-- Stale-authorization resolver: every minute it looks for payments still CREATED long after they were saved
-- (outcome unknown). CREATED rows are few and short-lived; the partial index keeps that query off the whole table.
CREATE INDEX payment_created_stale_idx ON payment (created_at) WHERE status = 'CREATED';
