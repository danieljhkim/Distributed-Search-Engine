#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   INDEX_URL="http://localhost:8080/api/v1/index" DOCS_JSON="./docs.json" ./bulk_index.sh
#
# Optional:
#   CONCURRENCY=4 TIMEOUT=10 RETRIES=3

INDEX_URL="${INDEX_URL:-http://localhost:8080/api/v1/index}"
CONCURRENCY="${CONCURRENCY:-4}"
TIMEOUT="${TIMEOUT:-10}"
RETRIES="${RETRIES:-3}"

DOCS_JSON="${DOCS_JSON:-docs.json}"

if ! command -v jq >/dev/null 2>&1; then
  echo "ERROR: jq is required (brew install jq)" >&2
  exit 1
fi

if [[ ! -f "$DOCS_JSON" ]]; then
  echo "ERROR: docs file not found: $DOCS_JSON" >&2
  exit 1
fi

# Validate JSON early (docs file must be a pure JSON array, not markdown with snippets)
if ! jq -e '.' "$DOCS_JSON" >/dev/null 2>&1; then
  echo "ERROR: $DOCS_JSON is not valid JSON." >&2
  echo "Tip: ensure it's a pure JSON array: [ {..}, {..} ] (no markdown headers or comments)." >&2
  exit 1
fi

post_one() {
  # Read one compact JSON object from stdin
  local doc
  doc="$(cat)"

  local id partitionId
  id="$(echo "$doc" | jq -r '.id // empty')"
  partitionId="$(echo "$doc" | jq -r '.partitionId // empty')"

  local label
  label="${partitionId:-unknown}${id:+/$id}"

  local attempt=1
  while true; do
    # Use stdin to avoid argv length limits; capture HTTP status for better errors
    local tmp_body http_code
    tmp_body="$(mktemp)"

    http_code="$(
      printf '%s' "$doc" |
        curl -sS --max-time "$TIMEOUT" \
          -H "Content-Type: application/json" \
          --data-binary @- \
          -o "$tmp_body" \
          -w '%{http_code}' \
          "$INDEX_URL" || echo 000
    )"

    # Read at most 300 chars of response for debugging
    local body_snip
    body_snip="$(head -c 300 "$tmp_body" | tr '\n' ' ' | tr '\r' ' ' )"
    rm -f "$tmp_body"

    if [[ "$http_code" =~ ^2 ]]; then
      echo "OK   $label"
      return 0
    fi

    # Don't retry on client errors (4xx) except 429
    if [[ "$http_code" =~ ^4 ]] && [[ "$http_code" != "429" ]]; then
      echo "FAIL $label status=$http_code body=$body_snip" >&2
      return 1
    fi

    if (( attempt >= RETRIES )); then
      echo "FAIL $label status=$http_code body=$body_snip (after $attempt attempts)" >&2
      return 1
    fi

    echo "RETRY $label status=$http_code (attempt $attempt/$RETRIES)" >&2
    attempt=$((attempt + 1))
    sleep 0.5
  done
}

# Stream docs and run up to CONCURRENCY jobs at a time (portable: no wait -n)
failures=0
pids=()

while IFS= read -r doc; do
  {
    if ! printf '%s' "$doc" | post_one; then
      exit 1
    fi
  } &

  pids+=("$!")

  # If we hit concurrency, wait for the oldest PID to finish
  if (( ${#pids[@]} >= CONCURRENCY )); then
    pid0="${pids[0]}"
    if ! wait "$pid0"; then
      failures=$((failures + 1))
    fi
    # shift
    pids=("${pids[@]:1}")
  fi

done < <(jq -c '.[]' "$DOCS_JSON")

# Wait for remaining jobs
for pid in "${pids[@]}"; do
  if ! wait "$pid"; then
    failures=$((failures + 1))
  fi
done

if (( failures > 0 )); then
  echo "ERROR: $failures document(s) failed." >&2
  exit 1
fi

echo "Done."