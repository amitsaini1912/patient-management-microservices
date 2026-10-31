#!/bin/sh
# Day 22: starts everything the k6 checkout test goes through, in a SEPARATE compose project (pm-loadtest: own,
# empty databases, your dev data untouched), with load-test settings:
#   - random secrets in load-test/.env.loadtest (created once, gitignored)
#   - the gateway's per-user rate limits raised: k6 logs in as ONE user, otherwise we'd measure the limiter
#   - no OpenTelemetry agent, risk engine's span export off (tracing cost is not what we measure)
#   - risk check ON (BILLING_RISK_ENABLED=true), acquirer = payment-network-simulator, cards via card-vault
# Java services run on the host as plain `java -jar` (logs + pids in load-test/run/). Stop: sh load-test/stop-stack.sh
#
#   sh load-test/start-stack.sh
set -eu
cd "$(dirname "$0")/.."
ENV=load-test/.env.loadtest
RUN=load-test/run
COMPOSE="docker compose -p pm-loadtest --env-file $ENV -f docker-compose.yml -f load-test/compose.loadtest.yml"

if [ ! -f "$ENV" ]; then
  r() { openssl rand -base64 32; }
  h() { openssl rand -hex 16; }
  cat > "$ENV" <<EOF
BILLING_DB_USER=billing
BILLING_DB_PASSWORD=$(h)
VAULT_DB_USER=vault
VAULT_DB_PASSWORD=$(h)
SIMULATOR_API_KEY=$(h)
SIMULATOR_WEBHOOK_SECRET=$(h)
BILLING_AUDIT_KEY=$(r)
VAULT_ENCRYPTION_KEYS=1:$(r)
VAULT_ACTIVE_KEY_VERSION=1
VAULT_FINGERPRINT_KEY=$(r)
CARD_VAULT_KEY_GATEWAY=$(h)
CARD_VAULT_KEY_BILLING=$(h)
CARD_VAULT_KEY_SIMULATOR=$(h)
RISK_API_KEY_BILLING=$(h)
GRAFANA_ADMIN_PASSWORD=$(h)
JWT_SECRET=$(openssl rand -base64 48)
EOF
  echo "created $ENV"
fi

set -a; . "./$ENV"; set +a
# same value on both sides of the webhook
export GATEWAY_WEBHOOK_SECRET="$SIMULATOR_WEBHOOK_SECRET"
export BILLING_RISK_ENABLED=true
export AUTH_SERVICE_URL=http://localhost:4005 BILLING_SERVICE_URL=http://localhost:4001 \
       CARD_VAULT_URL=http://localhost:4020 PATIENT_SERVICE_URL=http://localhost:4000
# one k6 user drives all traffic: lift the per-user buckets far above any rate we test
export GATEWAY_PAYMENTS_RATE_PER_SECOND=100000 GATEWAY_PAYMENTS_BURST=200000 \
       GATEWAY_CARDS_RATE_PER_SECOND=100000 GATEWAY_CARDS_BURST=200000
# extra JVM / Spring settings for an experiment, e.g. BILLING_EXTRA="--spring.datasource.hikari.maximum-pool-size=20"
BILLING_EXTRA=${BILLING_EXTRA:-}

echo "== compose (project pm-loadtest)"
$COMPOSE up -d --build --wait billing-db vault-db kafka redis risk-engine prometheus grafana

# auth-service has no DB in compose (k8s gives it its own Postgres): use a separate "auth" database in billing-db
# here. Only /login touches it; the gateway's per-request /validate is JWT parsing only.
$COMPOSE exec -T billing-db psql -U "$BILLING_DB_USER" -d billing -tAc "select 1 from pg_database where datname='auth'" \
  | grep -q 1 || $COMPOSE exec -T billing-db psql -U "$BILLING_DB_USER" -d billing -qc "create database auth"
AUTH_DB="--spring.datasource.url=jdbc:postgresql://localhost:5434/auth --spring.datasource.username=$BILLING_DB_USER
  --spring.datasource.password=$BILLING_DB_PASSWORD --spring.jpa.hibernate.ddl-auto=update --spring.sql.init.mode=always"

mkdir -p "$RUN"
# java: $JAVA_HOME, else the JDK 21 the repo is built with (PROGRESS environment notes), else PATH
JAVA_HOME=${JAVA_HOME:-$HOME/.jdks/ms-21.0.12.1}
JAVA=$JAVA_HOME/bin/java
[ -x "$JAVA" ] || JAVA=$(command -v java || true)
[ -n "$JAVA" ] && [ -x "$JAVA" ] || { echo "!! no java found: set JAVA_HOME to a JDK 21" >&2; exit 1; }
start() { # name port jar [args]
  name=$1; port=$2; jar=$3; shift 3
  if curl -sf "http://localhost:$port/actuator/health" >/dev/null 2>&1; then
    echo "!! something already answers on :$port - stop it first (sh load-test/stop-stack.sh)" >&2; exit 1
  fi
  setsid nohup "$JAVA" -Xms512m -Xmx1g -jar "$jar" "$@" > "$RUN/$name.log" 2>&1 &
  echo $! > "$RUN/$name.pid"
  echo "started $name (pid $!, :$port)"
}
wait_up() { # name port
  i=0
  until curl -sf "http://localhost:$2/actuator/health" >/dev/null 2>&1; do
    i=$((i + 1)); [ $i -gt 90 ] && { echo "!! $1 not healthy after 90 s, see $RUN/$1.log" >&2; exit 1; }
    sleep 1
  done
  echo "$1 up"
}

echo "== java services"
start card-vault 4020 card-vault/target/card-vault-0.0.1-SNAPSHOT.jar
start simulator 4010 payment-network-simulator/target/payment-network-simulator-0.0.1-SNAPSHOT.jar
# shellcheck disable=SC2086
start auth 4005 auth-service/target/auth-service-0.0.1-SNAPSHOT.jar $AUTH_DB
# shellcheck disable=SC2086
start billing 4001 billing-service/target/billing-service-0.0.1-SNAPSHOT.jar $BILLING_EXTRA
start gateway 4004 api-gateway/target/api-gateway-0.0.1-SNAPSHOT.jar
for s in "card-vault 4020" "simulator 4010" "auth 4005" "billing 4001" "gateway 4004"; do wait_up $s; done
curl -sf http://localhost:8000/ready >/dev/null && echo "risk-engine ready" || echo "!! risk-engine /ready failed"
echo "== ready. Prometheus http://localhost:9090  Grafana http://localhost:3000"
