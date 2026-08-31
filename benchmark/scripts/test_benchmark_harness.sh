#!/usr/bin/env bash
set -euo pipefail

BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
for script in "$BENCH_DIR"/scripts/*.sh; do bash -n "$script"; done
jq -e '.representative | (.BM25 | length > 0) and (.SEMANTIC | length > 0) and (.HYBRID | length > 0)' "$BENCH_DIR/config/query-mixes.json" >/dev/null
grep -q 'ci-smoke:' "$BENCH_DIR/config/scenarios.yaml"
grep -q 'capacity:' "$BENCH_DIR/config/scenarios.yaml"
grep -q 'repetitions: 5' "$BENCH_DIR/config/scenarios.yaml"
grep -q "createSearchWorkload('SEMANTIC')" "$BENCH_DIR/k6/semantic_search.js"
grep -q 'expected document is returned' "$BENCH_DIR/k6/search_workload.js"
grep -q 'fan-out metadata is successful' "$BENCH_DIR/k6/search_workload.js"
grep -q 'structured error semantics' "$BENCH_DIR/k6/overload_search.js"
grep -q 'refreshOrCommitVisibilitySeconds' "$BENCH_DIR/scripts/ingest_dataset.sh"
grep -q 'datasetManifest' "$BENCH_DIR/scripts/collect_metadata.sh"
grep -q 'run_capacity_benchmark.sh' "$BENCH_DIR/Makefile"
grep -q 'DSEARCH_BENCH_REPETITIONS' "$BENCH_DIR/scripts/run_capacity_benchmark.sh"
semantic_plan="$(make -C "$BENCH_DIR" -n semantic)"
grep -q 'run_gateway_benchmark.sh "semantic"' <<<"$semantic_plan"
semantic_case="$(awk '/semantic\)/,/;;/' "$BENCH_DIR/scripts/run_gateway_benchmark.sh")"
grep -q 'semantic_search.js' <<<"$semantic_case"
! grep -q 'hybrid_search.js' <<<"$semantic_case"
smoke_case="$(awk '/smoke\)/,/;;/' "$BENCH_DIR/scripts/run_gateway_benchmark.sh")"
grep -q 'semantic_search.js' <<<"$smoke_case"
echo 'benchmark harness contract checks passed'
