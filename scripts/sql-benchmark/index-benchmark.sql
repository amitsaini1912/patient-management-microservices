-- Composite-index benchmark for the analytics "claims by department + date range" search.
-- Run:  scripts/sql-benchmark/run.sh   (throwaway postgres:16 container, nothing else touched)
-- Same schema as analytic-service V1 + the V3 index. 2,000,000 visits and 2,000,000 claims, 8 departments, 3 years.
\timing off
CREATE TABLE visit (id UUID PRIMARY KEY, patient_id UUID NOT NULL, visit_date DATE NOT NULL, department VARCHAR(100) NOT NULL);
CREATE TABLE claim (id UUID PRIMARY KEY, visit_id UUID NOT NULL, claim_amount NUMERIC(10,2) NOT NULL,
                    paid_amount NUMERIC(10,2) NOT NULL, status VARCHAR(20) NOT NULL);
SELECT setseed(0.42);
INSERT INTO visit
SELECT gen_random_uuid(), gen_random_uuid(), DATE '2023-01-01' + (random() * 1095)::int,
       (ARRAY['Cardiology','Orthopedics','Neurology','Oncology','Pediatrics','Radiology','Dermatology','ENT'])[1 + (random()*7)::int]
FROM generate_series(1, 2000000);
INSERT INTO claim
SELECT gen_random_uuid(), v.id, round((random()*10000)::numeric, 2), 0,
       (ARRAY['APPROVED','DENIED','PENDING'])[1 + (random()*2)::int]
FROM visit v;
CREATE INDEX idx_claim_visit ON claim (visit_id);   -- FK index, present in both runs
VACUUM ANALYZE visit; VACUUM ANALYZE claim;

\echo '===== BEFORE (no composite index) ====='
EXPLAIN (ANALYZE, BUFFERS)
SELECT c.id, c.claim_amount, c.status, v.visit_date
FROM visit v JOIN claim c ON c.visit_id = v.id
WHERE v.department = 'Cardiology' AND v.visit_date BETWEEN DATE '2025-03-01' AND DATE '2025-03-07';

CREATE INDEX idx_visit_dept_date ON visit (department, visit_date);   -- = analytic-service V3
ANALYZE visit;

\echo '===== AFTER (composite index department, visit_date) ====='
EXPLAIN (ANALYZE, BUFFERS)
SELECT c.id, c.claim_amount, c.status, v.visit_date
FROM visit v JOIN claim c ON c.visit_id = v.id
WHERE v.department = 'Cardiology' AND v.visit_date BETWEEN DATE '2025-03-01' AND DATE '2025-03-07';
