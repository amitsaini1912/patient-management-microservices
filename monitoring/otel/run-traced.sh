#!/bin/sh
# Runs a service jar with the OpenTelemetry Java agent, exporting to Jaeger from docker-compose (OTLP/HTTP :4318).
#   docker compose up -d jaeger
#   sh monitoring/otel/run-traced.sh billing-service billing-service/target/billing-service-0.0.1-SNAPSHOT.jar [args]
# Traces: http://localhost:16686. The service's own env (DB passwords, keys ...) still has to be set by you.
set -eu
[ $# -ge 2 ] || { echo "usage: $0 <service-name> <jar> [args...]" >&2; exit 2; }
NAME=$1; JAR=$2; shift 2
DIR=$(cd "$(dirname "$0")" && pwd)
[ -f "$DIR/opentelemetry-javaagent.jar" ] || sh "$DIR/get-agent.sh"
export OTEL_SERVICE_NAME="$NAME"
export OTEL_EXPORTER_OTLP_ENDPOINT="${OTEL_EXPORTER_OTLP_ENDPOINT:-http://localhost:4318}"
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
export OTEL_METRICS_EXPORTER=none OTEL_LOGS_EXPORTER=none    # traces only today; metrics/logs are Day 20b
# per-service agent settings, if any (e.g. billing-service.properties)
CONFIG=""
[ -f "$DIR/$NAME.properties" ] && CONFIG="-Dotel.javaagent.configuration-file=$DIR/$NAME.properties"
exec java -javaagent:"$DIR/opentelemetry-javaagent.jar" $CONFIG -jar "$JAR" "$@"
