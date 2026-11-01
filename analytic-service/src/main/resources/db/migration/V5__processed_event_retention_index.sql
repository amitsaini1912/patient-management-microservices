-- Retention for processed_event (ProcessedEventCleanup): the hourly delete finds old rows by processed_at.
CREATE INDEX processed_event_processed_at_idx ON processed_event (processed_at);
