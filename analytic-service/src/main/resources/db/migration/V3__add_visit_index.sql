-- Composite index for "department + date range" lookups on the visit table.
-- Equality column (department) first, range column (visit_date) second —
-- this order lets the index jump to the department, then walk the date range in order.
CREATE INDEX idx_visit_dept_date ON visit (department,

                                           visit_date);