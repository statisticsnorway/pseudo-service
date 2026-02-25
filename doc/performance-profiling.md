# Profiling pseudonymization hot path

This project includes two profiler helpers for the DAEAD field benchmark:

- `scripts/perf/run-jfr-benchmark.sh`
- `scripts/perf/run-async-profiler.sh`

## 1) JFR (built into JDK)

Run:

```bash
./scripts/perf/run-jfr-benchmark.sh
```

Output:

- `target/performance/pseudo-service-benchmark.jfr`

Open this recording in Java Mission Control.

## 2) async-profiler flamegraph

Run wall-clock profile:

```bash
./scripts/perf/run-async-profiler.sh wall 15
```

Run CPU profile:

```bash
./scripts/perf/run-async-profiler.sh cpu 15
```

Output:

- `target/performance/flamegraph-<event>.html`
- `target/performance/flamegraph-<event>.collapsed`
- `target/performance/benchmark-under-profiler.log`

The script auto-downloads async-profiler into `.tools/` on first run.

## 3) Quick collapsed-stack analysis

```bash
./scripts/perf/analyze-collapsed.py target/performance/flamegraph-cpu.collapsed
```

This prints top sampled frames and top frames under service/core/tink packages.
