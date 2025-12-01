#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
BENCH_DIR="$( cd "$SCRIPT_DIR/.." && pwd )"
SEARCH_TYPE="${1:-HYBRID}"

export DSEARCH_QUERYNODE_ADDR="${DSEARCH_QUERYNODE_ADDR:-localhost:6000}"

echo "Running ghz ${SEARCH_TYPE} benchmark against $DSEARCH_QUERYNODE_ADDR"

# Build JSON payload safely to avoid quoting issues
PAYLOAD=$(jq -n \
  --arg q "${DSEARCH_BENCH_QUERY:-hola hello world}" \
  --arg st "${SEARCH_TYPE}" \
  '{queryString: $q, searchType: $st, page: 0, size: 30}')

ghz \
  --insecure \
  --proto "$BENCH_DIR/../dk.proto/src/main/proto/query.proto" \
  --call "dsearch.query.QueryService.Search" \
  -d "$PAYLOAD" \
  -c "${DSEARCH_BENCH_CONCURRENCY:-20}" \
  -n "${DSEARCH_BENCH_TOTAL:-2000}" \
  "$DSEARCH_QUERYNODE_ADDR"