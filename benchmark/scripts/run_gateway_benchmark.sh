#!/usr/bin/env bash
set -euo pipefail

target="${1:?target is required}"
scope="${2:?scope is required}"
BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
results_dir="$BENCH_DIR/results"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
run_dir="$results_dir/run-${timestamp}-${target}"
mkdir -p "$run_dir"

case "$target" in
  bm25) scripts=(bm25_search.js) ;;
  semantic) scripts=(semantic_search.js) ;;
  hybrid) scripts=(hybrid_search.js) ;;
  overload) scripts=(overload_search.js) ;;
  failure) scripts=(failure_search.js) ;;
  smoke) scripts=(bm25_search.js semantic_search.js hybrid_search.js) ;;
  capacity) scripts=(bm25_search.js semantic_search.js hybrid_search.js overload_search.js) ;;
  *) echo "unknown benchmark target: $target" >&2; exit 2 ;;
esac

export DSEARCH_BENCH_REPORT_DIR="$run_dir"
"$BENCH_DIR/scripts/collect_metadata.sh" > "$run_dir/metadata.json"
"$BENCH_DIR/scripts/ingest_dataset.sh" "${DSEARCH_BENCH_DATASET:-$BENCH_DIR/datasets/ci-smoke.jsonl}"
for script in "${scripts[@]}"; do
  k6 run --summary-export "$run_dir/${script%.js}-summary.json" "$BENCH_DIR/k6/$script" 2>&1 | tee "$run_dir/${script%.js}.log"
done
jq --arg target "$target" --arg scope "$scope" --arg generatedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" '. + {target:$target, scope:$scope, generatedAt:$generatedAt, rawArtifacts:["metadata.json","ingestion.json"]}' "$run_dir/metadata.json" > "$run_dir/run.json"
echo "Benchmark artifacts: $run_dir"
