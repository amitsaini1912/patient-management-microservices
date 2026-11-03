# SQL tuning: N+1 queries and a composite index

The first database work in this project (July 2026, analytic-service), before the payments work. Both results can be
re-run by anyone.

## 1. Hibernate N+1: 6 queries → 1

`Claim` has a lazy `@ManyToOne` to `Visit`. Loading claims and then reading each claim's visit sends one query for the
claims plus one query per visit:

```java
List<Claim> findByStatus(String status);                         // naive: 1 + N queries

@Query("SELECT c FROM Claim c JOIN FETCH c.visit WHERE c.status = :status")
List<Claim> findByStatusWithVisit(@Param("status") String status); // fixed: 1 query
```

**Measured** by counting the statements Hibernate actually prepares (`hibernate.generate_statistics`), on the seed
data (5 APPROVED claims on 5 visits):

| Method | SQL statements |
|---|---|
| `findByStatus` + `claim.getVisit()` | **6** (1 + 5) |
| `findByStatusWithVisit` (`JOIN FETCH`) | **1** |

Test: `analytic-service/src/test/java/org/pm/analyticservice/ClaimQueryCountTest.java` (fails if the count changes).

Why not just `FetchType.EAGER`? It would join the visit on *every* claim query, even the ones that don't need it,
and still causes N+1 with some queries. Fetch what each use case needs, where it needs it.

## 2. Composite index: claims search on 2 million rows, ~68 ms → ~6 ms (about 11x)

The report query: claims for one department in a date range (join `claim` → `visit`).

```sql
CREATE INDEX idx_visit_dept_date ON visit (department, visit_date);   -- analytic-service V3
```

**Column order matters**: equality column (`department`) first, range column (`visit_date`) second. The index jumps
to the department, then reads only the date range. The other order (`visit_date, department`) would scan the whole
date range and filter department afterwards.

**Measured** with `scripts/sql-benchmark/run.sh` (throwaway `postgres:16` container): 2,000,000 visits + 2,000,000
claims, 8 departments, 3 years; the query returns 955 rows. Only the composite index differs between runs (the
`claim(visit_id)` index exists in both). 5 warm runs each, `EXPLAIN ANALYZE` execution time:

| | Plan | Runs (ms) | Median |
|---|---|---|---|
| Without index | Parallel Seq Scan on visit (2 workers), 2M rows read, 1.99M filtered out | 78, 61, 68, 71, 49 | **~68 ms** |
| Without index, 1 core (`max_parallel_workers_per_gather=0`) | Seq Scan | 137, 186, 139 | ~139 ms |
| With `(department, visit_date)` | Bitmap Index Scan on idx_visit_dept_date, 955 rows read | 10, 4.8, 6.8, 5.8, 4.8 | **~6 ms** |

Laptop numbers (i5-13500H), measured 2026-11-03. The speedup grows with the table: the seq scan is O(rows), the index
read is O(matching rows).

## Other SQL in analytic-service

`ClaimRepository`: JOIN + GROUP BY reports (revenue and denial rate per department), `RANK() OVER` ranking, and a CTE
with `SUM() OVER` running total and `LAG()` month-over-month change. Schema changes go through Flyway (V1–V5).
