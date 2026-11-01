#!/bin/sh
# Payments in the pm-loadtest billing DB by status (+ CREATED older than 1 minute = outcome still unknown).
# Used by chaos.sh after each run; run it by hand later to see what the stale-authorization resolver (ADR 0024) did.
#   sh load-test/payment-status.sh
set -eu
cd "$(dirname "$0")/.."
. ./load-test/.env.loadtest
docker compose -p pm-loadtest --env-file load-test/.env.loadtest -f docker-compose.yml -f load-test/compose.loadtest.yml \
  exec -T billing-db psql -U "$BILLING_DB_USER" -d billing -c "
    SELECT status, count(*) AS payments,
           count(*) FILTER (WHERE status = 'CREATED' AND created_at < now() - interval '1 minute') AS created_over_1_min,
           count(*) FILTER (WHERE failure_reason = 'not_received_by_acquirer') AS resolver_not_received
    FROM payment GROUP BY status ORDER BY status;"
