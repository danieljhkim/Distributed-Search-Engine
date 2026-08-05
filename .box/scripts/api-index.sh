#!/usr/bin/env sh
set -eu

. "$(dirname "$0")/_lib.sh"

JSON="${1:-}"
if [ -z "$JSON" ]; then
  JSON=$(cat)
fi

curl -sS -X POST "http://localhost:8080/api/v1/index" \
  -H "Content-Type: application/json" \
  -d "$JSON"
