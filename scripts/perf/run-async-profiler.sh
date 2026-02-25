#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
TOOLS_DIR="$ROOT_DIR/.tools"
OUT_DIR="$ROOT_DIR/target/performance"
ASYNC_PROFILER_VERSION="4.3"
ASYNC_PROFILER_ZIP="async-profiler-${ASYNC_PROFILER_VERSION}-macos.zip"
ASYNC_PROFILER_URL="https://github.com/async-profiler/async-profiler/releases/download/v${ASYNC_PROFILER_VERSION}/${ASYNC_PROFILER_ZIP}"
ASYNC_PROFILER_DIR="$TOOLS_DIR/async-profiler-${ASYNC_PROFILER_VERSION}-macos"
ASPROF_BIN="$ASYNC_PROFILER_DIR/bin/asprof"

EVENT="${1:-wall}"
DURATION_SECONDS="${2:-20}"

mkdir -p "$TOOLS_DIR" "$OUT_DIR"

if [[ ! -x "$ASPROF_BIN" ]]; then
  echo "Installing async-profiler ${ASYNC_PROFILER_VERSION} into $TOOLS_DIR ..."
  curl -L --fail "$ASYNC_PROFILER_URL" -o "$TOOLS_DIR/$ASYNC_PROFILER_ZIP"
  rm -rf "$ASYNC_PROFILER_DIR"
  unzip -q "$TOOLS_DIR/$ASYNC_PROFILER_ZIP" -d "$TOOLS_DIR"
fi

SVG_FILE="$OUT_DIR/flamegraph-${EVENT}.html"
COLLAPSED_FILE="$OUT_DIR/flamegraph-${EVENT}.collapsed"

echo "Starting benchmark JVM..."
"$ROOT_DIR/mvnw" \
  -Dtest=PseudoServiceDaeadPerformanceTest \
  -Dsurefire.forkCount=1 \
  -Dsurefire.reuseForks=false \
  -Dpseudo.performance.enabled=true \
  -Dpseudo.performance.batchSize=10000 \
  -Dpseudo.performance.warmupRounds=5 \
  -Dpseudo.performance.measureRounds=240 \
  test > "$OUT_DIR/benchmark-under-profiler.log" 2>&1 &

MVN_PID=$!
trap 'kill "$MVN_PID" 2>/dev/null || true' EXIT

echo "Waiting for surefire JVM PID..."
TARGET_PID=""
for _ in {1..120}; do
  CHILD_PID="$(pgrep -P "$MVN_PID" | head -n1 || true)"
  if [[ -n "$CHILD_PID" ]]; then
    TARGET_PID="$(pgrep -P "$CHILD_PID" | head -n1 || true)"
  fi
  if [[ -n "$TARGET_PID" ]]; then
    break
  fi
  sleep 0.5
done

if [[ -z "$TARGET_PID" ]]; then
  echo "Could not find surefire JVM. See $OUT_DIR/benchmark-under-profiler.log"
  exit 1
fi

echo "Profiling PID $TARGET_PID for ${DURATION_SECONDS}s ($EVENT) ..."
"$ASPROF_BIN" -d "$DURATION_SECONDS" -e "$EVENT" -f "$SVG_FILE" "$TARGET_PID"
"$ASPROF_BIN" -d "$DURATION_SECONDS" -e "$EVENT" -o collapsed -f "$COLLAPSED_FILE" "$TARGET_PID"

wait "$MVN_PID"
trap - EXIT

echo "Flamegraph written to: $SVG_FILE"
echo "Collapsed stacks written to: $COLLAPSED_FILE"
