#!/usr/bin/env bash
set -euo pipefail

dataset="${1:?dataset JSONL path is required}"
gateway="${DSEARCH_GATEWAY_BASE_URL:-http://localhost:8080}${DSEARCH_GATEWAY_INDEX_PATH:-/api/v1/index}"
report_dir="${DSEARCH_BENCH_REPORT_DIR:-}"
[[ -f "$dataset" ]] || { echo "dataset not found: $dataset" >&2; exit 2; }
[[ -n "$report_dir" ]] && mkdir -p "$report_dir"

started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
started_epoch="$(date +%s)"
total=0; succeeded=0; failed=0
response_file="$(mktemp)"
trap 'rm -f "$response_file"' EXIT
while IFS= read -r document || [[ -n "$document" ]]; do
  [[ -z "$document" ]] && continue
  total=$((total + 1))
  response="$(curl --silent --show-error --output "$response_file" --write-out '%{http_code}' --request POST "$gateway" --header 'Content-Type: application/json' --data "$document")"
  if [[ "$response" == 200 || "$response" == 201 ]] && jq -e '.success == true and (.id | type == "string")' "$response_file" >/dev/null; then
    succeeded=$((succeeded + 1))
  else
    failed=$((failed + 1))
    echo "index failed for document $total (HTTP $response)" >&2
  fi
done < "$dataset"
elapsed=$(( $(date +%s) - started_epoch ))
visibility_seconds=null
refresh_query="${DSEARCH_BENCH_REFRESH_QUERY:-}"
if [[ -n "$refresh_query" ]]; then
  refresh_started=$SECONDS
  deadline=$((SECONDS + ${DSEARCH_BENCH_REFRESH_TIMEOUT_SECONDS:-30}))
  visible=false
  while ((SECONDS < deadline)); do
    search_status="$(curl --silent --output "$response_file" --write-out '%{http_code}' --request POST "${DSEARCH_GATEWAY_BASE_URL:-http://localhost:8080}${DSEARCH_GATEWAY_SEARCH_PATH:-/api/v1/search}" --header 'Content-Type: application/json' --data "$(jq -nc --arg query "$refresh_query" --arg partition "${DSEARCH_BENCH_PARTITION_ID:-bench}" '{query:$query,partitionId:$partition,page:0,pageSize:10,searchType:"BM25",highlight:false}')" || true)"
    if [[ "$search_status" == 200 ]] && jq -e --arg id "${DSEARCH_BENCH_REFRESH_EXPECTED_ID:?refresh expected id is required when refresh query is set}" '(.hits | map(.docId) | index($id)) != null' "$response_file" >/dev/null; then
      visible=true
      break
    fi
    sleep 0.2
  done
  visibility_seconds=$((SECONDS - refresh_started))
  if [[ "$visible" != true ]]; then
    failed=$((failed + 1))
    echo "refresh/commit visibility was not observed within ${DSEARCH_BENCH_REFRESH_TIMEOUT_SECONDS:-30}s" >&2
  fi
fi
report="$(jq -n --arg started "$started" --arg dataset "$dataset" --arg endpoint "$gateway" --argjson total "$total" --argjson succeeded "$succeeded" --argjson failed "$failed" --argjson elapsedSeconds "$elapsed" --argjson visibilitySeconds "$visibility_seconds" '{startedAt:$started,dataset:$dataset,endpoint:$endpoint,total:$total,succeeded:$succeeded,failed:$failed,elapsedSeconds:$elapsedSeconds,refreshOrCommitVisibilitySeconds:$visibilitySeconds}')"
if [[ -n "$report_dir" ]]; then printf '%s\n' "$report" > "$report_dir/ingestion.json"; else printf '%s\n' "$report"; fi
[[ "$failed" == 0 && "$succeeded" == "$total" ]]
