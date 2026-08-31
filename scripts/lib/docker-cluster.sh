# shellcheck shell=bash
#
# Shared helpers for the Docker Compose cluster gates.
#
# This file only declares functions. Source it after the caller has defined:
#
#   repo_root        absolute path of the checkout
#   compose_file     absolute path of docker-compose.yml
#   project_name     Compose project name owned by the caller
#   compose          bash array holding the fully qualified `docker compose ...` prefix
#   tls_root         writable scratch directory for generated mTLS material
#   diagnostics_dir  directory that receives captured evidence
#
# The HTTP helpers publish their result through the HTTP_STATUS and HTTP_BODY
# globals so callers can assert on both the status line and the body.

gateway_base_url=${DSEARCH_GATEWAY_URL:-http://localhost:19080}
partition_id=${DSEARCH_PARTITION_ID:-tenant-a}

HTTP_STATUS=
HTTP_BODY=

log() {
  printf '[%s] %s\n' "${DSEARCH_LOG_TAG:-docker-cluster}" "$*"
}

fail() {
  printf '[%s] ERROR: %s\n' "${DSEARCH_LOG_TAG:-docker-cluster}" "$*" >&2
  return 1
}

require_commands() {
  local command
  for command in "$@"; do
    command -v "$command" >/dev/null || fail "required command is unavailable: $command"
  done
}

capture_diagnostics() {
  mkdir -p "$diagnostics_dir"
  "${compose[@]}" ps --all >"$diagnostics_dir/compose-ps.txt" 2>&1 || true
  "${compose[@]}" images >"$diagnostics_dir/compose-images.txt" 2>&1 || true
  "${compose[@]}" config >"$diagnostics_dir/compose-config.yaml" 2>&1 || true
  "${compose[@]}" logs --no-color --timestamps >"$diagnostics_dir/compose.log" 2>&1 || true
  curl --silent --show-error --max-time 5 "$gateway_base_url/health" \
    >"$diagnostics_dir/gateway-health.json" 2>"$diagnostics_dir/gateway-health.stderr" || true
  curl --silent --show-error --max-time 5 "$gateway_base_url/readyz" \
    >"$diagnostics_dir/gateway-readiness.json" 2>"$diagnostics_dir/gateway-readiness.stderr" || true
  curl --silent --show-error --max-time 5 "$gateway_base_url/cluster/health" \
    >"$diagnostics_dir/cluster-health.json" 2>"$diagnostics_dir/cluster-health.stderr" || true

  local container_id
  while IFS= read -r container_id; do
    if [[ -n "$container_id" ]]; then
      docker inspect "$container_id" >>"$diagnostics_dir/container-inspect.json" 2>&1 || true
    fi
  done < <("${compose[@]}" ps --all --quiet 2>/dev/null || true)
}

generate_ca() {
  openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 1 \
    -subj "/CN=dsearch-e2e-ca" \
    -keyout "$tls_root/ca.key" \
    -out "$tls_root/ca.crt" >/dev/null 2>&1
}

generate_identity() {
  local directory=$1
  local dns_name=$2
  local spiffe_uri=$3
  local service_dir="$tls_root/$directory"
  local extension_file="$tls_root/$directory.ext"

  mkdir -p "$service_dir"
  openssl req -new -newkey rsa:2048 -sha256 -nodes \
    -subj "/CN=$dns_name" \
    -keyout "$service_dir/tls.key" \
    -out "$tls_root/$directory.csr" >/dev/null 2>&1
  printf '%s\n' \
    "subjectAltName=DNS:$dns_name,URI:$spiffe_uri" \
    "extendedKeyUsage=serverAuth,clientAuth" \
    "keyUsage=critical,digitalSignature,keyEncipherment" >"$extension_file"
  openssl x509 -req -sha256 -days 1 \
    -in "$tls_root/$directory.csr" \
    -CA "$tls_root/ca.crt" \
    -CAkey "$tls_root/ca.key" \
    -CAserial "$tls_root/ca.srl" \
    -CAcreateserial \
    -extfile "$extension_file" \
    -out "$service_dir/tls.crt" >/dev/null 2>&1
  cp "$tls_root/ca.crt" "$service_dir/ca.crt"
  # The short-lived key is mounted read-only and must be readable by the fixed
  # 10001:10001 container identity even when the host runner uses another UID.
  chmod 0444 "$service_dir/tls.key"
}

# Generates the full production-profile identity set used by docker-compose.yml
# and exports DSEARCH_TLS_DIR so Compose can resolve its per-service mounts.
generate_cluster_identities() {
  generate_ca
  generate_identity coordinator dsearch-coordinator spiffe://dsearch/node/coordinator/c0
  generate_identity gateway dsearch-gateway spiffe://dsearch/admin/gw0
  generate_identity query-node-0 dsearch-query-0 spiffe://dsearch/node/query/qn0
  generate_identity index-node-0 dsearch-index-0 spiffe://dsearch/node/index/in0
  generate_identity index-node-1 dsearch-index-1 spiffe://dsearch/node/index/in1
  export DSEARCH_TLS_DIR="$tls_root"
}

http_request() {
  local method=$1
  local path=$2
  local payload=${3:-}
  local response_file="$tls_root/http-response.json"
  local curl_args=(
    --silent
    --show-error
    --connect-timeout 5
    --max-time 15
    --output "$response_file"
    --write-out '%{http_code}'
    --request "$method"
  )
  if [[ -n "$payload" ]]; then
    curl_args+=(--header 'Content-Type: application/json' --data-binary "$payload")
  fi

  : >"$response_file"
  if HTTP_STATUS=$(curl "${curl_args[@]}" "$gateway_base_url$path"); then
    HTTP_BODY=$(<"$response_file")
  else
    HTTP_STATUS=000
    HTTP_BODY=$(<"$response_file")
  fi
}

assert_json() {
  local body=$1
  local filter=$2
  local description=$3
  if ! jq -e "$filter" <<<"$body" >/dev/null; then
    printf '[%s] Assertion failed: %s\n%s\n' "${DSEARCH_LOG_TAG:-docker-cluster}" "$description" "$body" >&2
    return 1
  fi
}

await_gateway_ready() {
  local timeout_seconds=${1:-420}
  local deadline=$((SECONDS + timeout_seconds))
  while ((SECONDS < deadline)); do
    http_request GET /readyz
    if [[ "$HTTP_STATUS" == "200" ]] && jq -e '.status == "UP"' <<<"$HTTP_BODY" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  fail "gateway did not become ready within ${timeout_seconds}s; last status=$HTTP_STATUS body=$HTTP_BODY"
}

await_cluster_index_count() {
  local expected=$1
  local timeout_seconds=$2
  local deadline=$((SECONDS + timeout_seconds))
  while ((SECONDS < deadline)); do
    http_request GET /cluster/health
    if [[ "$HTTP_STATUS" =~ ^(200|503)$ ]] \
      && jq -e --argjson expected "$expected" \
        '.indexNodes | length == $expected' <<<"$HTTP_BODY" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  fail "cluster did not report ${expected} index node(s) within ${timeout_seconds}s; last status=$HTTP_STATUS body=$HTTP_BODY"
}

search_until() {
  local payload=$1
  local filter=$2
  local description=$3
  local timeout_seconds=${4:-30}
  local deadline=$((SECONDS + timeout_seconds))
  while ((SECONDS < deadline)); do
    http_request POST /api/v1/search "$payload"
    if [[ "$HTTP_STATUS" == "200" ]] && jq -e "$filter" <<<"$HTTP_BODY" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  fail "$description did not become true within ${timeout_seconds}s; last status=$HTTP_STATUS body=$HTTP_BODY"
}

index_document_success() {
  local id=$1
  local title=$2
  local content=$3
  local category=$4
  local year=$5
  local payload
  payload=$(jq -nc \
    --arg id "$id" \
    --arg partitionId "$partition_id" \
    --arg title "$title" \
    --arg content "$content" \
    --arg category "$category" \
    --arg year "$year" \
    '{id:$id, partitionId:$partitionId, fields:{title:$title, content:$content, category:$category, year:$year}}')
  http_request POST /api/v1/index "$payload"
  [[ "$HTTP_STATUS" == "200" ]] || fail "index request for $id returned HTTP $HTTP_STATUS: $HTTP_BODY"
  assert_json "$HTTP_BODY" ".id == \"$id\" and .success == true" "index response confirms $id"
}

state_property() {
  local key=$1
  local state=$2
  sed -n "s/^${key}=//p" <<<"$state" | tail -n 1
}
