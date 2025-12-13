#!/usr/bin/env bash
set -euo pipefail

# Simple Gateway search tester following .box/contracts
# Usage examples:
#   scripts/test_search.sh "neural networks"
#   scripts/test_search.sh "vector databases" HYBRID 0 5
#   scripts/test_search.sh "bm25" LEXICAL 0 10 shard-0

GATEWAY_URL=${GATEWAY_URL:-"http://localhost:8080"}
HEALTH_PATH=${HEALTH_PATH:-"/actuator/health"}
SEARCH_PATH=${SEARCH_PATH:-"/api/v1/search"}

query=${1:-}
mode=${2:-LEXICAL}       # LEXICAL | SEMANTIC | HYBRID
from=${3:-0}
size=${4:-10}
shardId=${5:-}

if [[ -z "$query" ]]; then
  echo "Usage: $0 <query> [mode] [from] [size] [shardId]" >&2
  exit 2
fi

# Generate a lightweight correlation id
ts=$(date +%s%3N)
cid="devbox-${ts}"

echo "Checking gateway health at ${GATEWAY_URL}${HEALTH_PATH}..."
health_status=$(curl -sS -m 3 "${GATEWAY_URL}${HEALTH_PATH}" | tr -d '\n') || {
  echo "ERROR: Gateway health check failed" >&2
  exit 1
}
echo "Health: ${health_status}"

# Build JSON payload per search_request.schema.json
payload=$(jq -nc \
  --arg q "$query" \
  --arg mode "$mode" \
  --argjson from "$from" \
  --argjson size "$size" \
  --arg shard "$shardId" \
  '{query: $q, mode: $mode, from: $from, size: $size} + ( ($shard | length>0) // false | if . then {shardId: $shard} else {} end )')

echo "Sending search to ${GATEWAY_URL}${SEARCH_PATH} (mode=${mode}, from=${from}, size=${size})"

response=$(curl -sS -m 10 -X POST "${GATEWAY_URL}${SEARCH_PATH}" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: ${cid}" \
  -d "$payload") || {
  echo "ERROR: Search request failed" >&2
  exit 1
}

# Pretty-print if jq is available, else raw
if command -v jq >/dev/null 2>&1; then
  echo "$response" | jq .
else
  echo "$response"
fi

echo "CorrelationId: ${cid}"
