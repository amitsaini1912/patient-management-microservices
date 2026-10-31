#!/bin/sh
# Day 22: stops what start-stack.sh started. Java services by the pids it saved (never pkill by name).
#   sh load-test/stop-stack.sh          # keep the pm-loadtest databases (next run continues on the same data)
#   sh load-test/stop-stack.sh --wipe   # also delete the pm-loadtest volumes (only that project's, not your dev data)
set -eu
cd "$(dirname "$0")/.."
RUN=load-test/run
for f in "$RUN"/*.pid; do
  [ -f "$f" ] || continue
  pid=$(cat "$f")
  if kill "$pid" 2>/dev/null; then echo "stopped $(basename "$f" .pid) ($pid)"; fi
  rm -f "$f"
done
DOWN="down"
[ "${1:-}" = "--wipe" ] && DOWN="down -v"
# shellcheck disable=SC2086
docker compose -p pm-loadtest --env-file load-test/.env.loadtest -f docker-compose.yml -f load-test/compose.loadtest.yml $DOWN
