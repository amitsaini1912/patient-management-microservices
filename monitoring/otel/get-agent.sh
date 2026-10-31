#!/bin/sh
# Downloads the OpenTelemetry Java agent next to this script (Day 20a, ADR 0019), checksum-verified. Not in git.
#   sh monitoring/otel/get-agent.sh
set -eu
VERSION=2.10.0
SHA256=d05f6e36fac8db629263a6aaec2841cc934d064d7b19bfe38425b604b8b54926
DIR=$(cd "$(dirname "$0")" && pwd)
JAR="$DIR/opentelemetry-javaagent.jar"
if [ -f "$JAR" ] && echo "$SHA256  $JAR" | sha256sum -c - >/dev/null 2>&1; then
  echo "agent $VERSION already at $JAR"; exit 0
fi
curl -fsSL -o "$JAR.part" \
  "https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/$VERSION/opentelemetry-javaagent-$VERSION.jar"
echo "$SHA256  $JAR.part" | sha256sum -c -
mv "$JAR.part" "$JAR"
echo "agent $VERSION -> $JAR"
