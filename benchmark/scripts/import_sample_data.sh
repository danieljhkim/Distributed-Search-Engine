#!/usr/bin/env bash
set -euo pipefail

# ============================================================
# dsearch - Dataset Import Script
# Usage:
#   ./import_sample_data.sh datasets/small.jsonl
# ============================================================

SCRIPT_NAME="$(basename "$0")"

# ------------------------------
# Configurable Environment Vars
# ------------------------------
GATEWAY_URL="${DSEARCH_GATEWAY_BASE_URL:-http://localhost:8080}"
INDEX_PATH="${DSEARCH_GATEWAY_INDEX_PATH:-/api/v1/index}"
SHARD_FIELD="${DSEARCH_DOC_SHARD_FIELD:-shardId}"
DEFAULT_SHARD_ID="${DSEARCH_DEFAULT_SHARD_ID:-0}"

URL="${GATEWAY_URL}${INDEX_PATH}"

# ------------------------------
# Input Validation
# ------------------------------
if [[ $# -lt 1 ]]; then
  echo "Usage: ${SCRIPT_NAME} <dataset.jsonl>"
  exit 1
fi

DATASET="$1"

if [[ ! -f "$DATASET" ]]; then
  echo "Error: dataset file not found: $DATASET"
  exit 1
fi

# ------------------------------
# Counters
# ------------------------------
TOTAL=0
SUCCESS=0
FAILED=0

START_TIME=$(date +%s)

echo "=================================================="
echo " dsearch - Dataset Import"
echo "--------------------------------------------------"
echo " Dataset:       $DATASET"
echo " Endpoint:      $URL"
echo " Shard field:   $SHARD_FIELD"
echo " Default shard: $DEFAULT_SHARD_ID"
echo "=================================================="
echo ""

# ------------------------------
# Process dataset line-by-line
# ------------------------------
while IFS= read -r line; do

  ((TOTAL++))

  # Skip empty lines
  if [[ -z "$line" ]]; then
    continue
  fi

  # Validate JSON
  if ! echo "$line" | jq . >/dev/null 2>&1; then
    echo "[WARN] Skipping invalid JSON on line $TOTAL"
    ((FAILED++))
    continue
  fi

  # Append shardId if missing
  if ! echo "$line" | jq -e ".${SHARD_FIELD}" >/dev/null 2>&1; then
    line=$(echo "$line" | jq ". + {\"${SHARD_FIELD}\": \"${DEFAULT_SHARD_ID}\"}")
  fi

  # Send to Gateway
  RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$URL" \
    -H "Content-Type: application/json" \
    -d "$line")

  if [[ "$RESPONSE" == "200" || "$RESPONSE" == "201" ]]; then
    ((SUCCESS++))
  else
    ((FAILED++))
    echo "[ERROR] Failed to index document on line $TOTAL (HTTP $RESPONSE)"
  fi

  # Progress indicator every 50 docs
  if (( TOTAL % 50 == 0 )); then
    echo "Indexed: $TOTAL documents (Success: $SUCCESS, Failed: $FAILED)"
  fi

done < "$DATASET"

# ------------------------------
# Summary
# ------------------------------
END_TIME=$(date +%s)
ELAPSED=$((END_TIME - START_TIME))

echo ""
echo "=================================================="
echo " Import Complete"
echo "--------------------------------------------------"
echo " Total documents:   $TOTAL"
echo " Successfully sent: $SUCCESS"
echo " Failed:            $FAILED"
echo " Duration:          ${ELAPSED}s"
echo " Rate:              $((TOTAL / (ELAPSED > 0 ? ELAPSED : 1))) docs/sec"
echo "=================================================="