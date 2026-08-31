#!/usr/bin/env bash
set -euo pipefail

BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
concurrency="${DSEARCH_BENCH_CAPACITY_CONCURRENCY:-1 4 16 64 128}"
repetitions="${DSEARCH_BENCH_REPETITIONS:-5}"
[[ "$repetitions" =~ ^[1-9][0-9]*$ ]] || { echo "DSEARCH_BENCH_REPETITIONS must be positive" >&2; exit 2; }
[[ -f "${DSEARCH_BENCH_DATASET:-$BENCH_DIR/datasets/generated/capacity.jsonl}" ]] || {
  echo "capacity dataset is missing; run make generate-dataset first" >&2
  exit 2
}

for vus in $concurrency; do
  for repetition in $(seq 1 "$repetitions"); do
    export DSEARCH_BENCH_PROFILE=capacity
    export DSEARCH_BENCH_VUS="$vus"
    export DSEARCH_RUN_LABEL="capacity-vus-${vus}-run-${repetition}"
    export DSEARCH_BENCH_WARMUP="${DSEARCH_BENCH_CAPACITY_WARMUP:-60s}"
    export DSEARCH_BENCH_DURATION="${DSEARCH_BENCH_CAPACITY_DURATION:-300s}"
    export DSEARCH_BENCH_REFRESH_QUERY=""
    "$BENCH_DIR/scripts/run_gateway_benchmark.sh" capacity all
  done
done
