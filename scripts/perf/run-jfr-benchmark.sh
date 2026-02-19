#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
OUT_DIR="$ROOT_DIR/target/performance"
JFR_FILE="$OUT_DIR/pseudo-service-benchmark.jfr"

mkdir -p "$OUT_DIR"

echo "Running benchmark with JFR recording..."
MAVEN_OPTS="-XX:StartFlightRecording=filename=$JFR_FILE,dumponexit=true,settings=profile" \
  "$ROOT_DIR/mvnw" \
  -Dtest=PseudoServiceDaeadPerformanceTest \
  -Dpseudo.performance.enabled=true \
  -Dpseudo.performance.batchSize=10000 \
  -Dpseudo.performance.warmupRounds=5 \
  -Dpseudo.performance.measureRounds=30 \
  test

echo "JFR written to: $JFR_FILE"
