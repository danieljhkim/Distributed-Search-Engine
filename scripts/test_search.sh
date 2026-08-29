#!/usr/bin/env bash
set -euo pipefail

# Simple Gateway search tester for the current SearchRequestDto contract.
# Usage examples:
#   scripts/test_search.sh "neural networks"
#   scripts/test_search.sh "vector databases" HYBRID 0 5
#   scripts/test_search.sh "bm25" LEXICAL 0 10 movies

GATEWAY_URL=${GATEWAY_URL:-"http://localhost:8080"}
HEALTH_PATH=${HEALTH_PATH:-"/actuator/health"}
SEARCH_PATH=${SEARCH_PATH:-"/api/v1/search"}

query=${1:-}
mode=${2:-LEXICAL}       # LEXICAL | SEMANTIC | HYBRID
offset=${3:-0}
size=${4:-10}
partitionId=${5:-default}

if [[ -z "$query" ]]; then
  echo "Usage: $0 <query> [mode] [offset] [size] [partitionId]" >&2
  exit 2
fi

case "$mode" in
  LEXICAL|SEMANTIC|HYBRID) ;;
  *)
    echo "ERROR: mode must be LEXICAL, SEMANTIC, or HYBRID (got '$mode')" >&2
    exit 2
    ;;
esac

if [[ ! "$offset" =~ ^[0-9]+$ ]]; then
  echo "ERROR: offset must be a non-negative integer (got '$offset')" >&2
  exit 2
fi

if [[ ! "$size" =~ ^[1-9][0-9]*$ ]]; then
  echo "ERROR: size must be a positive integer (got '$size')" >&2
  exit 2
fi

# The Gateway accepts page/pageSize, not an arbitrary Lucene-style offset.
if (( offset % size != 0 )); then
  echo "ERROR: offset '$offset' must be a multiple of size '$size' for the Gateway page API" >&2
  exit 2
fi
page=$((offset / size))

if [[ ! "$partitionId" =~ ^[A-Za-z0-9_-]{1,64}$ ]]; then
  echo "ERROR: partitionId must match [A-Za-z0-9_-]{1,64} (got '$partitionId')" >&2
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

# SearchRequestDto uses page/pageSize/searchType/partitionId. Keep the
# positional offset interface, but convert it to the equivalent page.
payload=$(jq -nc \
  --arg q "$query" \
  --arg partition "$partitionId" \
  --arg searchType "$mode" \
  --argjson page "$page" \
  --argjson pageSize "$size" \
  '{query: $q, partitionId: $partition, page: $page, pageSize: $pageSize, searchType: $searchType}')

echo "Sending search to ${GATEWAY_URL}${SEARCH_PATH} (mode=${mode}, offset=${offset}, size=${size}, partitionId=${partitionId})"

response_with_status=$(curl -sS -m 10 -w $'\n%{http_code}' -X POST "${GATEWAY_URL}${SEARCH_PATH}" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: ${cid}" \
  -d "$payload") || {
  echo "ERROR: Search request failed" >&2
  exit 1
}
http_status=${response_with_status##*$'\n'}
response=${response_with_status%$'\n'*}

if [[ "$http_status" != 2?? ]]; then
  echo "ERROR: Search request returned HTTP ${http_status}" >&2
  echo "$response" >&2
  exit 1
fi

if ! jq -e --argjson expectedPage "$page" --argjson expectedPageSize "$size" \
  '.page == $expectedPage and .pageSize == $expectedPageSize' <<<"$response" >/dev/null; then
  echo "ERROR: Gateway response page/pageSize do not match requested page/pageSize" >&2
  echo "$response" >&2
  exit 1
fi

# The current response DTO does not echo searchType. If a compatible Gateway
# does echo it, validate it too; the request payload above is the authoritative
# mode check for the current response shape.
if jq -e 'has("searchType")' <<<"$response" >/dev/null; then
  if ! jq -e --arg expected "$mode" '(.searchType | ascii_upcase) == $expected' <<<"$response" >/dev/null; then
    echo "ERROR: Gateway response searchType does not match requested mode" >&2
    echo "$response" >&2
    exit 1
  fi
fi

echo "Verified Gateway response page=${page}, pageSize=${size}, searchType=${mode}"

# Pretty-print if jq is available, else raw
if command -v jq >/dev/null 2>&1; then
  echo "$response" | jq .
else
  echo "$response"
fi

echo "CorrelationId: ${cid}"
