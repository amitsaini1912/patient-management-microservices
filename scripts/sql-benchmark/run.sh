#!/usr/bin/env bash
# Measures the composite index on 2M rows. Prints EXPLAIN ANALYZE before/after; repeat 5 times for a median.
set -euo pipefail
cd "$(dirname "$0")"
docker rm -f sql-bench >/dev/null 2>&1 || true
docker run -d --name sql-bench -e POSTGRES_PASSWORD=bench postgres:16 >/dev/null
until docker exec sql-bench pg_isready -U postgres >/dev/null 2>&1; do sleep 1; done; sleep 2
docker exec -i sql-bench psql -U postgres -q < index-benchmark.sql
# warm runs (data cached): only the composite index differs; the claim(visit_id) FK index stays in both.
Q="EXPLAIN ANALYZE SELECT c.id, c.claim_amount, c.status, v.visit_date FROM visit v JOIN claim c ON c.visit_id=v.id
   WHERE v.department='Cardiology' AND v.visit_date BETWEEN '2025-03-01' AND '2025-03-07';"
run() { docker exec sql-bench psql -U postgres -At -c "$1" -c "$Q" | grep 'Execution Time' | sed "s/^/$2 /"; }
for i in 1 2 3 4 5; do run "DROP INDEX IF EXISTS idx_visit_dept_date;" "without-index"; done
docker exec sql-bench psql -U postgres -q -c "CREATE INDEX idx_visit_dept_date ON visit (department, visit_date); ANALYZE visit;"
for i in 1 2 3 4 5; do run "SELECT 1;" "with-index   "; done
# single-core comparison (no parallel workers), closer to a small production box
for i in 1 2 3; do run "SET max_parallel_workers_per_gather=0; DROP INDEX IF EXISTS idx_visit_dept_date;" "1-core without"; done
docker rm -f sql-bench >/dev/null
