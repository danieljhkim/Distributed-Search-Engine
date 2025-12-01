#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
BENCH_DIR="$( cd "$SCRIPT_DIR/.." && pwd )"

export DSEARCH_GATEWAY_BASE_URL="${DSEARCH_GATEWAY_BASE_URL:-http://localhost:8080}"
export DSEARCH_GATEWAY_SEARCH_PATH="${DSEARCH_GATEWAY_SEARCH_PATH:-/api/v1/search}"
export DSEARCH_BENCH_VUS="${DSEARCH_BENCH_VUS:-5}"
export DSEARCH_BENCH_DURATION="${DSEARCH_BENCH_DURATION:-15s}"
export DSEARCH_BENCH_QUERY="${DSEARCH_BENCH_QUERY:-hola amigo, hello world}"
export DSEARCH_BENCH_PAGE="${DSEARCH_BENCH_PAGE:-0}"
export DSEARCH_BENCH_PAGE_SIZE="${DSEARCH_BENCH_PAGE_SIZE:-30}"

echo "Running k6 BM25 benchmark against $DSEARCH_GATEWAY_BASE_URL$DSEARCH_GATEWAY_SEARCH_PATH"
k6 run "$BENCH_DIR/k6/bm25_search.js"