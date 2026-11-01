#!/bin/sh
# Day 23: one chaos scenario against the running pm-loadtest stack (sh load-test/start-stack.sh first).
# k6 sends a steady 5 checkouts/s for 150 s (a client that retries 503/409 with the same Idempotency-Key);
# at t=30 s the fault starts, at t=90 s (db: t=60 s) it is healed, then 60 s of recovery are watched.
# Evidence -> load-test/results/chaos-<scenario>.json (k6), -server.txt (server diff), -timeline.txt (every 5 s).
#
#   sh load-test/chaos.sh kafka | risk | db | redis | vault | simulator     (TAG=<name>: separate result files)
set -eu
cd "$(dirname "$0")/.."
S=${1:?scenario: kafka | risk | db | redis | vault | simulator}
COMPOSE="docker compose -p pm-loadtest --env-file load-test/.env.loadtest -f docker-compose.yml -f load-test/compose.loadtest.yml"
# TAG=followups sh load-test/chaos.sh redis -> results/chaos-redis-followups.* (keeps the committed Day 23 files)
OUT=load-test/results/chaos-$S${TAG:+-$TAG}
RUN=load-test/run
FAULT_AT=30; HEAL_AT=90
pid() { cat "$RUN/$1.pid"; }

case $S in
  kafka)     fault() { $COMPOSE stop kafka; };                 heal() { $COMPOSE start kafka; } ;;
  risk)      fault() { docker pause pm-loadtest-risk-engine-1; }; heal() { docker unpause pm-loadtest-risk-engine-1; } ;;
  db)        fault() { $COMPOSE stop billing-db; };            heal() { $COMPOSE start billing-db; }; HEAL_AT=60 ;;
  redis)     fault() { $COMPOSE stop redis; };                 heal() { $COMPOSE start redis; } ;;
  # SIGSTOP = the process hangs (accepts TCP, never answers): worse than "down", the usual production failure
  vault)     fault() { kill -STOP "$(pid card-vault)"; };      heal() { kill -CONT "$(pid card-vault)"; } ;;
  simulator) fault() { kill -STOP "$(pid simulator)"; };       heal() { kill -CONT "$(pid simulator)"; } ;;
  *) echo "unknown scenario $S" >&2; exit 2 ;;
esac

metric() { # billing gauge value (first series), or "-"
  curl -s -m 2 http://localhost:4001/actuator/prometheus 2>/dev/null | awk -v m="$1" '$1 ~ "^"m"({|$)" {print $2; exit}' | grep . || echo "-"
}
sample() {
  printf '%5ss  outbox_pending=%-6s oldest_pending_age_s=%-8s risk_circuit=%s  (0 closed, 1 open, 2 half-open)\n' \
    "$1" "$(metric billing_outbox_pending)" "$(metric billing_outbox_oldest_pending_age_seconds)" \
    "$(metric billing_risk_circuit_state)"
}

python3 load-test/server_stats.py snapshot "$RUN/chaos-before.json" >/dev/null
: > "$OUT-timeline.txt"
k6 run -q -e PROFILE=chaos -e DURATION=150s --summary-export="$OUT.json" --out csv="$RUN/chaos-points.csv" \
  load-test/checkout.js > "$OUT-k6.txt" 2>&1 &
K6=$!
START=$(date +%s); faulted=""; healed=""; next_sample=0
while kill -0 $K6 2>/dev/null; do
  t=$(( $(date +%s) - START ))      # wall clock: stopping a container can take ~10 s
  [ -z "$faulted" ] && [ $t -ge $FAULT_AT ] && { faulted=1; echo "$(date +%T) t=${t}s FAULT: $S" | tee -a "$OUT-timeline.txt"; fault >/dev/null 2>&1; echo "$(date +%T) t=$(( $(date +%s) - START ))s fault in place" >> "$OUT-timeline.txt"; }
  [ -z "$healed" ] && [ $t -ge $HEAL_AT ] && { healed=1; echo "$(date +%T) t=${t}s HEAL: $S" | tee -a "$OUT-timeline.txt"; heal >/dev/null 2>&1; echo "$(date +%T) t=$(( $(date +%s) - START ))s healed" >> "$OUT-timeline.txt"; }
  [ $t -ge $next_sample ] && { sample $t >> "$OUT-timeline.txt"; next_sample=$(( t + 5 )); }
  sleep 1
done
wait $K6 || true    # thresholds crossed = non-zero exit; expected during chaos
sleep 15            # let the outbox drain / Prometheus scrape once more
sample "end+15" >> "$OUT-timeline.txt"
python3 load-test/server_stats.py diff "$RUN/chaos-before.json" > "$OUT-server.txt"
sh load-test/payment-status.sh > "$OUT-payments.txt" 2>&1 || true   # payments by status right after the run
# what each checkout ended as, and why clients retried (k6's summary doesn't split counters by tag)
python3 - "$RUN/chaos-points.csv" > "$OUT-outcomes.txt" <<'PY'
import csv, sys, collections
c = collections.Counter()
for row in csv.DictReader(open(sys.argv[1])):
    tags = dict(row)   # standard tags are columns; custom ones are in extra_tags as k=v&k=v
    tags.update(kv.split("=", 1) for kv in (row.get("extra_tags") or "").split("&") if "=" in kv)
    if row["metric_name"] == "payment_outcomes":
        c[("outcome", tags.get("status") or "?")] += int(float(row["metric_value"]))
    elif row["metric_name"] == "client_retries":
        c[("retry", f'{tags.get("step") or "?"} after {tags.get("status") or "?"}')] += int(float(row["metric_value"]))
    elif row["metric_name"] == "http_reqs":
        c[("http", f'{tags.get("name") or "?"} {tags.get("status") or "?"}')] += 1
for (kind, label), n in sorted(c.items()):
    print(f"{kind:8} {label:32} {n}")
PY
rm -f "$RUN/chaos-points.csv"
echo "== $S done: $OUT.json, $OUT-k6.txt, $OUT-server.txt, $OUT-timeline.txt"
cat "$OUT-outcomes.txt"; cat "$OUT-timeline.txt"
