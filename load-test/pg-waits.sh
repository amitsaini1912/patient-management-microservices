#!/bin/sh
# Day 22: what billing's database sessions are waiting on, sampled once a second (run in a 2nd terminal during k6,
# Ctrl+C to stop, then it prints the totals). Lock waits (wait_event_type=Lock) = transactions queueing behind each
# other's row locks; "ClientRead" = idle in transaction, waiting for the Java side.
#   sh load-test/pg-waits.sh
cd "$(dirname "$0")/.."
OUT=load-test/run/pg-waits.txt
mkdir -p load-test/run; : > "$OUT"
SQL="select coalesce(wait_event_type,'CPU/none') || ':' || coalesce(wait_event,'-') || ' ' || left(regexp_replace(query, '\s+', ' ', 'g'), 70)
     from pg_stat_activity where datname = 'billing' and state <> 'idle' and pid <> pg_backend_pid();"
trap 'echo; echo "== samples (session x seconds), top 15:"; sort "$OUT" | uniq -c | sort -rn | head -15; exit 0' INT TERM
echo "sampling billing-db every second -> $OUT (Ctrl+C to stop)"
while :; do
  docker compose -p pm-loadtest --env-file load-test/.env.loadtest -f docker-compose.yml -f load-test/compose.loadtest.yml \
    exec -T billing-db psql -U billing -d billing -Atc "$SQL" >> "$OUT" 2>/dev/null
  sleep 1
done
