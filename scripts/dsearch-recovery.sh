#!/usr/bin/env bash

set -Eeuo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
default_compose_file="$repo_root/docker-compose.yml"
default_config_file="$repo_root/dk.common/src/main/resources/app-config.docker.yaml"
default_pom_file="$repo_root/pom.xml"
recovery_metrics_file=${DSEARCH_RECOVERY_METRICS_FILE:-}
recovery_operation=

log() {
  printf '[dsearch-recovery] %s\n' "$*" >&2
}

fail() {
  printf '[dsearch-recovery] ERROR: %s\n' "$*" >&2
  if [[ "$recovery_operation" == restore ]]; then
    publish_recovery_metric restore_failure "$(date +%s)" || true
  fi
  return 1
}

# The optional textfile collector target keeps recovery metrics free of artifact IDs and project
# names. A node-exporter textfile collector or equivalent scraper owns serving this file.
publish_recovery_metric() {
  local event=$1 timestamp=$2 current_snapshot=0 restore_success=0 restore_failure=0 target temporary
  [[ -n "$recovery_metrics_file" ]] || return 0
  target=$recovery_metrics_file
  mkdir -p "$(dirname "$target")"
  if [[ -f "$target" ]]; then
    current_snapshot=$(sed -n 's/^dsearch_snapshot_last_successful_timestamp_seconds //p' "$target" | tail -n 1)
    restore_success=$(sed -n 's/^dsearch_restore_outcomes_total{outcome="success"} //p' "$target" | tail -n 1)
    restore_failure=$(sed -n 's/^dsearch_restore_outcomes_total{outcome="failure"} //p' "$target" | tail -n 1)
  fi
  [[ "$current_snapshot" =~ ^[0-9]+$ ]] || current_snapshot=0
  [[ "$restore_success" =~ ^[0-9]+$ ]] || restore_success=0
  [[ "$restore_failure" =~ ^[0-9]+$ ]] || restore_failure=0
  case "$event" in
    snapshot_success) current_snapshot=$timestamp ;;
    restore_success) restore_success=$((restore_success + 1)) ;;
    restore_failure) restore_failure=$((restore_failure + 1)) ;;
    *) return 2 ;;
  esac
  temporary="${target}.tmp.$$"
  {
    printf '# HELP dsearch_snapshot_last_successful_timestamp_seconds Unix timestamp of the last valid snapshot\n'
    printf '# TYPE dsearch_snapshot_last_successful_timestamp_seconds gauge\n'
    printf 'dsearch_snapshot_last_successful_timestamp_seconds %s\n' "$current_snapshot"
    printf '# HELP dsearch_restore_outcomes_total Completed restore attempts by bounded outcome\n'
    printf '# TYPE dsearch_restore_outcomes_total counter\n'
    printf 'dsearch_restore_outcomes_total{outcome="success"} %s\n' "$restore_success"
    printf 'dsearch_restore_outcomes_total{outcome="failure"} %s\n' "$restore_failure"
  } >"$temporary"
  mv "$temporary" "$target"
}

usage() {
  cat >&2 <<'EOF'
Usage:
  scripts/dsearch-recovery.sh snapshot --project NAME --output DIR --verification FILE [options]
  scripts/dsearch-recovery.sh validate --snapshot DIR [options]
  scripts/dsearch-recovery.sh restore --project EMPTY_NAME --snapshot DIR [options]
  scripts/dsearch-recovery.sh verify --verification FILE [--gateway-url URL]

Options:
  --compose-file FILE    Compose file (default: docker-compose.yml)
  --config FILE          Runtime configuration used to interpret the index
  --pom FILE             Root Maven POM used for project and Lucene versions
  --gateway-url URL      Public gateway base URL (default: specification value)
  --report FILE          Restore report destination
  --startup-timeout SEC  Gateway/cluster startup timeout (default: 420)
  --leave-stopped        Snapshot only: do not restart the source deployment

The verification JSON must contain a datasetId, an exact document-count probe,
and representative BM25, SEMANTIC, and HYBRID queries. Restore is fail-closed:
the destination Compose project must not already have containers or volumes.
EOF
}

require_command() {
  command -v "$1" >/dev/null || fail "required command is unavailable: $1"
}

sha256_file() {
  local file=$1
  if command -v sha256sum >/dev/null; then
    sha256sum "$file" | awk '{print $1}'
  elif command -v shasum >/dev/null; then
    shasum -a 256 "$file" | awk '{print $1}'
  else
    fail 'sha256sum or shasum is required'
  fi
}

file_size() {
  local file=$1
  if stat -c '%s' "$file" >/dev/null 2>&1; then
    stat -c '%s' "$file"
  else
    stat -f '%z' "$file"
  fi
}

read_pom_property() {
  local pom=$1
  local property=$2
  sed -n "s:.*<${property}>\([^<]*\)</${property}>.*:\1:p" "$pom" | head -n 1
}

read_state_property() {
  local file=$1
  local property=$2
  sed -n "s/^${property}=//p" "$file" | tail -n 1
}

json_array() {
  jq -nc --args '$ARGS.positional' -- "$@"
}

validate_verification_spec() {
  local specification=$1
  [[ -f "$specification" ]] || fail "verification specification is missing: $specification"
  jq -e '
    .schemaVersion == 1
    and (.datasetId | type == "string" and length > 0)
    and (.lastAcknowledgedWriteAt | type == "string" and length > 0)
    and (try (.lastAcknowledgedWriteAt | fromdateiso8601) catch false) != false
    and (.documentCount.partitionId | type == "string" and length > 0)
    and (.documentCount.query | type == "string" and length > 0)
    and (.documentCount.expected | type == "number" and . >= 0 and floor == .)
    and (.queries | type == "array" and length >= 3)
    and ([.queries[].searchType | ascii_upcase] | contains(["BM25"]))
    and ([.queries[].searchType | ascii_upcase] | contains(["SEMANTIC"]))
    and ([.queries[].searchType | ascii_upcase] | contains(["HYBRID"]))
    and all(.queries[];
      (.name | type == "string" and test("^[A-Za-z0-9][A-Za-z0-9._-]*$"))
      and (.partitionId | type == "string" and length > 0)
      and (.query | type == "string" and length > 0)
      and (.searchType | type == "string")
      and (.expectedDocIds | type == "array" and length > 0)
      and all(.expectedDocIds[]; type == "string" and length > 0))
  ' "$specification" >/dev/null || fail "verification specification has an invalid recovery contract: $specification"
}

http_search() {
  local gateway_url=$1
  local payload=$2
  local response_file=$3
  local status
  status=$(curl --silent --show-error --connect-timeout 5 --max-time 60 \
    --output "$response_file" --write-out '%{http_code}' \
    --header 'Content-Type: application/json' --data-binary "$payload" \
    "$gateway_url/api/v1/search") || return 1
  [[ "$status" == "200" ]] || {
    log "gateway search returned HTTP $status: $(<"$response_file")"
    return 1
  }
}

verify_gateway() {
  local specification=$1
  local gateway_url=$2
  local evidence_file=${3:-}
  local timeout_seconds=${4:-120}
  local scratch_dir
  scratch_dir=$(mktemp -d "${TMPDIR:-/tmp}/dsearch-recovery-verify.XXXXXX")
  local evidence_jsonl="$scratch_dir/evidence.jsonl"
  : >"$evidence_jsonl"

  local count_payload count_response expected_count deadline
  count_response="$scratch_dir/count-response.json"
  expected_count=$(jq -r '.documentCount.expected' "$specification")
  count_payload=$(jq -c '.documentCount | {
      query, partitionId, page: 0, pageSize: ((.expected + 10) | if . > 1000 then 1000 else . end),
      searchType: "BM25", highlight: false
    }' "$specification")
  deadline=$((SECONDS + timeout_seconds))
  while true; do
    if http_search "$gateway_url" "$count_payload" "$count_response" \
      && jq -e --argjson expected "$expected_count" '.totalHits == $expected' "$count_response" >/dev/null; then
      break
    fi
    ((SECONDS < deadline)) || {
      rm -rf "$scratch_dir"
      fail "document-count verification did not reach $expected_count before timeout"
    }
    sleep 2
  done
  jq -nc --arg name document-count --argjson response "$(<"$count_response")" \
    '{name:$name,response:$response}' >>"$evidence_jsonl"

  local query name partition_id search_type fusion_strategy payload response expected_ids
  while IFS= read -r query; do
    name=$(jq -r '.name' <<<"$query")
    partition_id=$(jq -r '.partitionId' <<<"$query")
    search_type=$(jq -r '.searchType | ascii_upcase' <<<"$query")
    fusion_strategy=$(jq -r '.fusionStrategy // "RRF"' <<<"$query")
    expected_ids=$(jq -c '.expectedDocIds' <<<"$query")
    payload=$(jq -nc \
      --arg query "$(jq -r '.query' <<<"$query")" \
      --arg partition "$partition_id" \
      --arg search_type "$search_type" \
      --arg fusion_strategy "$fusion_strategy" \
      '{query:$query,partitionId:$partition,page:0,pageSize:100,searchType:$search_type,
        fusionStrategy:$fusion_strategy,highlight:false}')
    response="$scratch_dir/$name-response.json"
    deadline=$((SECONDS + timeout_seconds))
    while true; do
      if http_search "$gateway_url" "$payload" "$response" \
        && jq -e --argjson expected "$expected_ids" \
          '([.hits[].docId] as $actual | all($expected[]; . as $id | $actual | index($id) != null))' \
          "$response" >/dev/null; then
        break
      fi
      ((SECONDS < deadline)) || {
        rm -rf "$scratch_dir"
        fail "$name ($search_type) verification did not return every expected document before timeout"
      }
      sleep 2
    done
    jq -nc --arg name "$name" --arg searchType "$search_type" --argjson response "$(<"$response")" \
      '{name:$name,searchType:$searchType,response:$response}' >>"$evidence_jsonl"
  done < <(jq -c '.queries[]' "$specification")

  if [[ -n "$evidence_file" ]]; then
    jq -s --arg verifiedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
      '{verifiedAt:$verifiedAt,checks:.}' "$evidence_jsonl" >"$evidence_file"
  fi
  rm -rf "$scratch_dir"
}

manifest_file_paths() {
  jq -r '.files[].path' "$1"
}

validate_artifact() {
  local snapshot=$1
  local config_file=$2
  local pom_file=$3
  local allow_partial=${4:-false}
  local manifest="$snapshot/manifest.json"

  [[ -d "$snapshot" ]] || fail "snapshot artifact is missing: $snapshot"
  [[ "$allow_partial" == 'true' || "$(basename "$snapshot")" != *'.partial.'* ]] \
    || fail "partial snapshot directories are never valid artifacts: $snapshot"
  [[ -f "$manifest" ]] || fail "snapshot manifest is missing: $manifest"
  [[ ! -L "$snapshot" ]] || fail "snapshot root must not be a symlink: $snapshot"
  if find "$snapshot" -type l -print -quit | grep -q .; then
    fail "snapshot contains a symlink"
  fi

  jq -e '
    .schemaVersion == 1
    and .state == "complete"
    and (.artifactId | type == "string" and test("^[A-Za-z0-9][A-Za-z0-9._-]*$"))
    and (.createdAt | type == "string" and length > 0)
    and (try (.createdAt | fromdateiso8601) catch false) != false
    and (try (.recoveryPointAt | fromdateiso8601) catch false) != false
    and (try (.lastAcknowledgedWriteAt | fromdateiso8601) catch false) != false
    and ((.recoveryPointAt | fromdateiso8601) >= (.lastAcknowledgedWriteAt | fromdateiso8601))
    and (.source.commit | test("^[0-9a-f]{40}$"))
    and (.compatibility.configSha256 | test("^[0-9a-f]{64}$"))
    and (.compatibility.pomSha256 | test("^[0-9a-f]{64}$"))
    and (.compatibility.mavenRevision | type == "string" and length > 0)
    and (.compatibility.luceneVersion | type == "string" and length > 0)
    and .compatibility.coordinatorStateFormat == 1
    and (.topology.epoch | type == "string" and length > 0)
    and (.topology.version | type == "number" and . >= 1 and floor == .)
    and (.topology.indexServices | type == "array" and length > 0)
    and (.topology.queryServices | type == "array")
    and all(.topology.indexServices[]; test("^[A-Za-z0-9][A-Za-z0-9._-]*$"))
    and all(.topology.queryServices[]; test("^[A-Za-z0-9][A-Za-z0-9._-]*$"))
    and ([.topology.indexServices[]] | length == (unique | length))
    and ([.topology.queryServices[]] | length == (unique | length))
    and (.verification.datasetId | type == "string" and length > 0)
    and .verification.specification == "metadata/verification.json"
    and .verification.preSnapshotEvidence == "metadata/pre-snapshot-verification.json"
    and (.files | type == "array" and length > 0)
    and all(.files[];
      (.path | type == "string" and length > 0
        and (startswith("/") | not)
        and (split("/") | index("..") | not))
      and (.size | type == "number" and . >= 0 and floor == .)
      and (.sha256 | test("^[0-9a-f]{64}$")))
    and ([.files[].path] | length == (unique | length))
  ' "$manifest" >/dev/null || fail "snapshot manifest contract is invalid: $manifest"

  local listed actual_paths listed_paths
  actual_paths=$(mktemp "${TMPDIR:-/tmp}/dsearch-recovery-actual.XXXXXX")
  listed_paths=$(mktemp "${TMPDIR:-/tmp}/dsearch-recovery-listed.XXXXXX")
  find "$snapshot" -type f ! -path "$manifest" -print \
    | sed "s|^$snapshot/||" | LC_ALL=C sort >"$actual_paths"
  manifest_file_paths "$manifest" | LC_ALL=C sort >"$listed_paths"
  if ! cmp -s "$actual_paths" "$listed_paths"; then
    diff -u "$listed_paths" "$actual_paths" >&2 || true
    rm -f "$actual_paths" "$listed_paths"
    fail "snapshot file inventory differs from the manifest"
  fi
  rm -f "$actual_paths" "$listed_paths"

  local entry relative file expected_sha expected_size actual_sha actual_size
  while IFS= read -r entry; do
    relative=$(jq -r '.path' <<<"$entry")
    expected_sha=$(jq -r '.sha256' <<<"$entry")
    expected_size=$(jq -r '.size' <<<"$entry")
    file="$snapshot/$relative"
    [[ -f "$file" ]] || fail "manifest artifact is missing: $relative"
    actual_size=$(file_size "$file")
    [[ "$actual_size" == "$expected_size" ]] \
      || fail "size mismatch for $relative: expected $expected_size, found $actual_size"
    actual_sha=$(sha256_file "$file")
    [[ "$actual_sha" == "$expected_sha" ]] \
      || fail "checksum mismatch for $relative: expected $expected_sha, found $actual_sha"
  done < <(jq -c '.files[]' "$manifest")

  [[ -f "$config_file" ]] || fail "current configuration is missing: $config_file"
  [[ -f "$pom_file" ]] || fail "current Maven POM is missing: $pom_file"
  local expected_config_sha expected_pom_sha current_config_sha current_pom_sha
  expected_config_sha=$(jq -r '.compatibility.configSha256' "$manifest")
  expected_pom_sha=$(jq -r '.compatibility.pomSha256' "$manifest")
  current_config_sha=$(sha256_file "$config_file")
  current_pom_sha=$(sha256_file "$pom_file")
  [[ "$(sha256_file "$snapshot/metadata/app-config.yaml")" == "$expected_config_sha" ]] \
    || fail 'captured runtime configuration differs from manifest compatibility metadata'
  [[ "$(sha256_file "$snapshot/metadata/pom.xml")" == "$expected_pom_sha" ]] \
    || fail 'captured Maven POM differs from manifest compatibility metadata'
  [[ "$current_config_sha" == "$expected_config_sha" ]] \
    || fail "incompatible runtime configuration: expected $expected_config_sha, found $current_config_sha"
  [[ "$current_pom_sha" == "$expected_pom_sha" ]] \
    || fail "incompatible build metadata: expected $expected_pom_sha, found $current_pom_sha"

  local current_revision current_lucene manifest_revision manifest_lucene
  current_revision=$(read_pom_property "$pom_file" revision)
  current_lucene=$(read_pom_property "$pom_file" lucene.version)
  manifest_revision=$(jq -r '.compatibility.mavenRevision' "$manifest")
  manifest_lucene=$(jq -r '.compatibility.luceneVersion' "$manifest")
  [[ "$current_revision" == "$manifest_revision" ]] \
    || fail "incompatible Maven revision: expected $manifest_revision, found $current_revision"
  [[ "$current_lucene" == "$manifest_lucene" ]] \
    || fail "incompatible Lucene version: expected $manifest_lucene, found $current_lucene"

  validate_verification_spec "$snapshot/metadata/verification.json"
  [[ -f "$snapshot/metadata/pre-snapshot-verification.json" ]] \
    || fail 'snapshot is missing pre-snapshot public verification evidence'
  [[ "$(jq -r '.datasetId' "$snapshot/metadata/verification.json")" == \
    "$(jq -r '.verification.datasetId' "$manifest")" ]] \
    || fail 'verification dataset identifier differs from manifest'
  [[ "$(tr -d '\n' <"$snapshot/metadata/source-commit.txt")" == \
    "$(jq -r '.source.commit' "$manifest")" ]] \
    || fail 'captured source commit differs from manifest'
  local captured_dirty=false
  [[ ! -s "$snapshot/metadata/source-status.txt" ]] || captured_dirty=true
  [[ "$captured_dirty" == "$(jq -r '.source.repositoryDirty' "$manifest")" ]] \
    || fail 'captured repository status differs from manifest'

  local state_file="$snapshot/payload/coordinator/coordinator-topology.properties"
  [[ -f "$state_file" ]] || fail 'snapshot is missing coordinator topology state'
  local state_format state_epoch state_version manifest_format manifest_epoch manifest_version
  state_format=$(read_state_property "$state_file" 'state\.format\.version')
  state_format=${state_format:-1}
  state_epoch=$(read_state_property "$state_file" 'topology\.epoch')
  state_version=$(read_state_property "$state_file" 'topology\.version')
  manifest_format=$(jq -r '.compatibility.coordinatorStateFormat' "$manifest")
  manifest_epoch=$(jq -r '.topology.epoch' "$manifest")
  manifest_version=$(jq -r '.topology.version' "$manifest")
  [[ "$state_format" == "$manifest_format" ]] \
    || fail "coordinator state format differs from manifest: $state_format != $manifest_format"
  [[ "$state_epoch" == "$manifest_epoch" ]] \
    || fail "coordinator epoch differs from manifest: $state_epoch != $manifest_epoch"
  [[ "$state_version" == "$manifest_version" ]] \
    || fail "coordinator topology version differs from manifest: $state_version != $manifest_version"

  local service
  while IFS= read -r service; do
    [[ -d "$snapshot/payload/index-nodes/$service" ]] \
      || fail "snapshot is missing Lucene payload directory for $service"
    [[ -d "$snapshot/payload/model-caches/$service" ]] \
      || fail "snapshot is missing model metadata/cache directory for $service"
  done < <(jq -r '.topology.indexServices[]' "$manifest")
  while IFS= read -r service; do
    [[ -d "$snapshot/payload/model-caches/$service" ]] \
      || fail "snapshot is missing model metadata/cache directory for $service"
  done < <(jq -r '.topology.queryServices[]' "$manifest")
}

compose_services_matching() {
  local pattern=$1
  "${compose[@]}" config --services | sed -n "/$pattern/p"
}

await_gateway_ready() {
  local gateway_url=$1
  local timeout_seconds=$2
  local deadline=$((SECONDS + timeout_seconds))
  while ((SECONDS < deadline)); do
    if curl --fail --silent --show-error --max-time 5 "$gateway_url/readyz" \
      | jq -e '.status == "UP"' >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  fail "gateway did not become ready within ${timeout_seconds}s"
}

await_topology() {
  local gateway_url=$1
  local expected_index_nodes=$2
  local timeout_seconds=$3
  local deadline=$((SECONDS + timeout_seconds))
  while ((SECONDS < deadline)); do
    if curl --silent --show-error --max-time 5 "$gateway_url/cluster/health" \
      | jq -e --argjson expected "$expected_index_nodes" \
        '.indexNodes | length == $expected' >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  fail "cluster did not reconstruct $expected_index_nodes index nodes within ${timeout_seconds}s"
}

copy_from_service() {
  local service=$1
  local source=$2
  local destination=$3
  [[ -n "$("${compose[@]}" ps --all --quiet "$service")" ]] \
    || fail "Compose service has no container to snapshot: $service"
  mkdir -p "$destination"
  "${compose[@]}" cp "$service:$source/." "$destination"
}

copy_to_service() {
  local source=$1
  local service=$2
  local destination=$3
  [[ -d "$source" ]] || fail "restore payload directory is missing: $source"
  "${compose[@]}" cp "$source/." "$service:$destination"
}

snapshot_command() {
  recovery_operation=snapshot
  local project= output= verification= gateway_url= leave_stopped=false
  local compose_file=$default_compose_file config_file=$default_config_file pom_file=$default_pom_file
  while (($#)); do
    case "$1" in
      --project) project=${2:?}; shift 2 ;;
      --output) output=${2:?}; shift 2 ;;
      --verification) verification=${2:?}; shift 2 ;;
      --gateway-url) gateway_url=${2:?}; shift 2 ;;
      --compose-file) compose_file=${2:?}; shift 2 ;;
      --config) config_file=${2:?}; shift 2 ;;
      --pom) pom_file=${2:?}; shift 2 ;;
      --leave-stopped) leave_stopped=true; shift ;;
      *) fail "unknown snapshot option: $1"; usage; return 2 ;;
    esac
  done
  [[ -n "$project" ]] || fail 'snapshot requires --project'
  [[ -n "$output" ]] || fail 'snapshot requires --output'
  [[ -n "$verification" ]] || fail 'snapshot requires --verification'
  [[ ! -e "$output" ]] || fail "snapshot output already exists: $output"
  validate_verification_spec "$verification"
  require_command docker
  require_command jq
  require_command curl
  require_command git
  docker info >/dev/null
  docker compose version >/dev/null

  gateway_url=${gateway_url:-$(jq -r '.gatewayUrl // "http://localhost:19080"' "$verification")}
  compose=(docker compose --project-name "$project" --file "$compose_file")
  local all_services=() running_services=() index_services=() query_services=() service
  while IFS= read -r service; do all_services+=("$service"); done < <("${compose[@]}" config --services)
  while IFS= read -r service; do index_services+=("$service"); done < <(compose_services_matching '^index-node-')
  while IFS= read -r service; do query_services+=("$service"); done < <(compose_services_matching '^query-node-')
  ((${#index_services[@]} > 0)) || fail 'Compose topology has no index-node-* services'
  while IFS= read -r service; do running_services+=("$service"); done \
    < <("${compose[@]}" ps --services --status running)
  for service in "${all_services[@]}"; do
    [[ " ${running_services[*]} " == *" $service "* ]] \
      || fail "snapshot requires a fully running deployment; $service is not running"
  done

  local output_parent stage preflight_evidence recovery_point_at restart_source=true
  output_parent=$(dirname "$output")
  mkdir -p "$output_parent"
  stage=$(mktemp -d "$output.partial.XXXXXX")
  preflight_evidence="$stage/metadata/pre-snapshot-verification.json"
  mkdir -p "$stage/metadata" "$stage/payload/index-nodes" "$stage/payload/model-caches"

  snapshot_exit() {
    local exit_code=$?
    trap - EXIT INT TERM
    if [[ "$restart_source" == "true" ]]; then
      log "Restarting source project $project"
      "${compose[@]}" up --detach --no-build >/dev/null || exit_code=1
      if ! await_gateway_ready "$gateway_url" 420; then
        exit_code=1
      fi
    fi
    if ((exit_code != 0)); then
      log "Incomplete staging directory retained for diagnosis and is not a valid snapshot: $stage"
    fi
    exit "$exit_code"
  }
  trap snapshot_exit EXIT INT TERM

  log "Verifying the public dataset before quiescing $project"
  verify_gateway "$verification" "$gateway_url" "$preflight_evidence" 120

  log 'Stopping ingress, freezing coordinator epoch, and committing Lucene writers'
  "${compose[@]}" stop --timeout 20 gateway
  "${compose[@]}" stop --timeout 20 coordinator
  if ((${#query_services[@]})); then
    "${compose[@]}" stop --timeout 20 "${query_services[@]}"
  fi
  "${compose[@]}" stop --timeout 20 "${index_services[@]}"
  [[ -z "$("${compose[@]}" ps --services --status running)" ]] \
    || fail 'snapshot consistency boundary failed: at least one service is still running'
  recovery_point_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)

  if [[ "${DSEARCH_RECOVERY_FAILPOINT:-}" == 'snapshot-after-quiesce' ]]; then
    fail 'injected interruption after the snapshot consistency boundary'
  fi

  copy_from_service coordinator /data "$stage/payload/coordinator"
  for service in "${query_services[@]}"; do
    copy_from_service "$service" /var/cache/dsearch "$stage/payload/model-caches/$service"
  done
  for service in "${index_services[@]}"; do
    copy_from_service "$service" /data/index "$stage/payload/index-nodes/$service"
    copy_from_service "$service" /var/cache/dsearch "$stage/payload/model-caches/$service"
  done

  if [[ "${DSEARCH_RECOVERY_FAILPOINT:-}" == 'snapshot-after-copy' ]]; then
    fail 'injected interruption after snapshot payload copy'
  fi

  cp "$config_file" "$stage/metadata/app-config.yaml"
  cp "$pom_file" "$stage/metadata/pom.xml"
  cp "$verification" "$stage/metadata/verification.json"
  "${compose[@]}" config >"$stage/metadata/compose-config.yaml"
  git -C "$repo_root" rev-parse HEAD >"$stage/metadata/source-commit.txt"
  git -C "$repo_root" status --porcelain=v1 >"$stage/metadata/source-status.txt"

  local state_file="$stage/payload/coordinator/coordinator-topology.properties"
  [[ -f "$state_file" ]] || fail "coordinator topology state is absent from the snapshot payload"
  local topology_epoch topology_version state_format commit revision lucene_version
  topology_epoch=$(read_state_property "$state_file" 'topology\.epoch')
  topology_version=$(read_state_property "$state_file" 'topology\.version')
  state_format=$(read_state_property "$state_file" 'state\.format\.version')
  state_format=${state_format:-1}
  [[ -n "$topology_epoch" && "$topology_version" =~ ^[0-9]+$ && "$topology_version" -ge 1 ]] \
    || fail 'coordinator topology state is malformed'
  [[ "$state_format" == '1' ]] || fail "unsupported coordinator state format: $state_format"
  commit=$(tr -d '\n' <"$stage/metadata/source-commit.txt")
  revision=$(read_pom_property "$pom_file" revision)
  lucene_version=$(read_pom_property "$pom_file" lucene.version)
  [[ -n "$revision" && -n "$lucene_version" ]] || fail 'Maven compatibility metadata is incomplete'

  local inventory_jsonl="$stage/.inventory.jsonl" files_json="$stage/.files.json"
  : >"$inventory_jsonl"
  local file relative size digest
  while IFS= read -r -d '' file; do
    relative=${file#"$stage/"}
    [[ "$relative" == '.inventory.jsonl' || "$relative" == '.files.json' || "$relative" == 'manifest.json' ]] \
      && continue
    size=$(file_size "$file")
    digest=$(sha256_file "$file")
    jq -nc --arg path "$relative" --arg sha256 "$digest" --argjson size "$size" \
      '{path:$path,size:$size,sha256:$sha256}' >>"$inventory_jsonl"
  done < <(find "$stage" -type f -print0 | LC_ALL=C sort -z)
  jq -s '.' "$inventory_jsonl" >"$files_json"

  local created_at artifact_id artifact_nonce last_acknowledged_at config_sha pom_sha dirty
  created_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  artifact_nonce=${stage##*.}
  artifact_id="dsearch-$(date -u +%Y%m%dT%H%M%SZ)-${commit:0:12}-$artifact_nonce"
  last_acknowledged_at=$(jq -r '.lastAcknowledgedWriteAt' "$verification")
  config_sha=$(sha256_file "$config_file")
  pom_sha=$(sha256_file "$pom_file")
  dirty=false
  [[ ! -s "$stage/metadata/source-status.txt" ]] || dirty=true
  jq -n \
    --arg artifactId "$artifact_id" \
    --arg createdAt "$created_at" \
    --arg recoveryPointAt "$recovery_point_at" \
    --arg lastAcknowledgedWriteAt "$last_acknowledged_at" \
    --arg sourceProject "$project" \
    --arg commit "$commit" \
    --argjson dirty "$dirty" \
    --arg configSha256 "$config_sha" \
    --arg pomSha256 "$pom_sha" \
    --arg mavenRevision "$revision" \
    --arg luceneVersion "$lucene_version" \
    --argjson coordinatorStateFormat "$state_format" \
    --arg epoch "$topology_epoch" \
    --argjson topologyVersion "$topology_version" \
    --argjson indexServices "$(json_array "${index_services[@]}")" \
    --argjson queryServices "$(json_array "${query_services[@]}")" \
    --arg datasetId "$(jq -r '.datasetId' "$verification")" \
    --slurpfile files "$files_json" \
    '{schemaVersion:1,state:"complete",artifactId:$artifactId,createdAt:$createdAt,
      recoveryPointAt:$recoveryPointAt,lastAcknowledgedWriteAt:$lastAcknowledgedWriteAt,
      source:{project:$sourceProject,commit:$commit,repositoryDirty:$dirty},
      compatibility:{configSha256:$configSha256,pomSha256:$pomSha256,
        mavenRevision:$mavenRevision,luceneVersion:$luceneVersion,
        coordinatorStateFormat:$coordinatorStateFormat},
      topology:{epoch:$epoch,version:$topologyVersion,indexServices:$indexServices,
        queryServices:$queryServices},
      verification:{datasetId:$datasetId,specification:"metadata/verification.json",
        preSnapshotEvidence:"metadata/pre-snapshot-verification.json"},
      files:$files[0]}' >"$stage/manifest.json"
  rm -f "$inventory_jsonl" "$files_json"

  validate_artifact "$stage" "$config_file" "$pom_file" true
  mv "$stage" "$output"
  stage=$output
  log "Published complete snapshot $artifact_id at $output"
  publish_recovery_metric snapshot_success "$(date +%s)"

  if [[ "$leave_stopped" == "true" ]]; then
    restart_source=false
  fi
  trap - EXIT INT TERM
  if [[ "$restart_source" == "true" ]]; then
    "${compose[@]}" up --detach --no-build >/dev/null
    await_gateway_ready "$gateway_url" 420
  fi
  jq -n --arg artifactId "$artifact_id" --arg path "$output" \
    --arg recoveryPointAt "$recovery_point_at" \
    '{status:"success",artifactId:$artifactId,path:$path,recoveryPointAt:$recoveryPointAt}'
}

validate_command() {
  local snapshot= config_file=$default_config_file pom_file=$default_pom_file
  while (($#)); do
    case "$1" in
      --snapshot) snapshot=${2:?}; shift 2 ;;
      --config) config_file=${2:?}; shift 2 ;;
      --pom) pom_file=${2:?}; shift 2 ;;
      *) fail "unknown validate option: $1"; usage; return 2 ;;
    esac
  done
  [[ -n "$snapshot" ]] || fail 'validate requires --snapshot'
  require_command jq
  validate_artifact "$snapshot" "$config_file" "$pom_file"
  jq -n --arg artifactId "$(jq -r '.artifactId' "$snapshot/manifest.json")" \
    '{status:"valid",artifactId:$artifactId}'
}

restore_command() {
  recovery_operation=restore
  local project= snapshot= report= gateway_url= startup_timeout=420
  local compose_file=$default_compose_file config_file=$default_config_file pom_file=$default_pom_file
  while (($#)); do
    case "$1" in
      --project) project=${2:?}; shift 2 ;;
      --snapshot) snapshot=${2:?}; shift 2 ;;
      --report) report=${2:?}; shift 2 ;;
      --gateway-url) gateway_url=${2:?}; shift 2 ;;
      --startup-timeout) startup_timeout=${2:?}; shift 2 ;;
      --compose-file) compose_file=${2:?}; shift 2 ;;
      --config) config_file=${2:?}; shift 2 ;;
      --pom) pom_file=${2:?}; shift 2 ;;
      *) fail "unknown restore option: $1"; usage; return 2 ;;
    esac
  done
  [[ -n "$project" ]] || fail 'restore requires --project'
  [[ -n "$snapshot" ]] || fail 'restore requires --snapshot'
  [[ "$startup_timeout" =~ ^[0-9]+$ && "$startup_timeout" -ge 1 ]] \
    || fail '--startup-timeout must be a positive integer'
  require_command docker
  require_command jq
  require_command curl
  validate_artifact "$snapshot" "$config_file" "$pom_file"
  docker info >/dev/null
  docker compose version >/dev/null

  local manifest="$snapshot/manifest.json" verification="$snapshot/metadata/verification.json"
  local source_project
  source_project=$(jq -r '.source.project' "$manifest")
  [[ "$project" != "$source_project" ]] \
    || fail "restore destination must differ from source project $source_project"
  gateway_url=${gateway_url:-$(jq -r '.gatewayUrl // "http://localhost:19080"' "$verification")}
  compose=(docker compose --project-name "$project" --file "$compose_file")

  [[ -z "$("${compose[@]}" ps --all --quiet)" ]] \
    || fail "restore destination project already has containers: $project"
  [[ -z "$(docker volume ls --filter "label=com.docker.compose.project=$project" --quiet)" ]] \
    || fail "restore destination project already has volumes: $project"

  local started_epoch started_at completed_at rto_seconds rpo_seconds artifact_id
  artifact_id=$(jq -r '.artifactId' "$manifest")
  report=${report:-"$(dirname "$snapshot")/restore-report-$artifact_id-$project.json"}
  [[ ! -e "$report" ]] || fail "restore report already exists: $report"
  started_epoch=$(date +%s)
  started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  log "Creating empty destination project $project for $artifact_id"
  "${compose[@]}" create --no-build
  if [[ "${DSEARCH_RECOVERY_FAILPOINT:-}" == 'restore-after-create' ]]; then
    fail 'injected interruption after empty restore project creation'
  fi

  local index_services=() query_services=() service first_index=true
  while IFS= read -r service; do index_services+=("$service"); done \
    < <(jq -r '.topology.indexServices[]' "$manifest")
  while IFS= read -r service; do query_services+=("$service"); done \
    < <(jq -r '.topology.queryServices[]' "$manifest")
  copy_to_service "$snapshot/payload/coordinator" coordinator /data
  "${compose[@]}" run --rm --no-deps --user 0 --entrypoint sh coordinator \
    -c 'chown -R 10001:10001 /data'
  for service in "${query_services[@]}"; do
    copy_to_service "$snapshot/payload/model-caches/$service" "$service" /var/cache/dsearch
    "${compose[@]}" run --rm --no-deps --user 0 --entrypoint sh "$service" \
      -c 'chown -R 10001:10001 /var/cache/dsearch'
  done
  for service in "${index_services[@]}"; do
    copy_to_service "$snapshot/payload/index-nodes/$service" "$service" /data/index
    copy_to_service "$snapshot/payload/model-caches/$service" "$service" /var/cache/dsearch
    "${compose[@]}" run --rm --no-deps --user 0 --entrypoint sh "$service" \
      -c 'chown -R 10001:10001 /data/index /var/cache/dsearch'
    if [[ "$first_index" == "true" && "${DSEARCH_RECOVERY_FAILPOINT:-}" == 'restore-after-first-index' ]]; then
      fail 'injected interruption after first restored Lucene volume'
    fi
    first_index=false
  done

  log "Starting restored project $project"
  "${compose[@]}" up --detach --no-build
  await_gateway_ready "$gateway_url" "$startup_timeout"
  await_topology "$gateway_url" "${#index_services[@]}" "$startup_timeout"

  local restored_state expected_epoch restored_epoch expected_version restored_version
  restored_state=$("${compose[@]}" exec --no-TTY coordinator \
    sh -c 'cat /data/coordinator-topology.properties')
  expected_epoch=$(jq -r '.topology.epoch' "$manifest")
  expected_version=$(jq -r '.topology.version' "$manifest")
  restored_epoch=$(read_state_property /dev/stdin 'topology\.epoch' <<<"$restored_state")
  restored_version=$(read_state_property /dev/stdin 'topology\.version' <<<"$restored_state")
  [[ "$restored_epoch" == "$expected_epoch" ]] \
    || fail "restored coordinator epoch changed: expected $expected_epoch, found $restored_epoch"
  [[ "$restored_version" =~ ^[0-9]+$ && "$restored_version" -ge "$expected_version" ]] \
    || fail "restored coordinator topology version regressed: expected >= $expected_version, found $restored_version"

  local verification_evidence
  verification_evidence=$(mktemp "${TMPDIR:-/tmp}/dsearch-recovery-restored.XXXXXX")
  verify_gateway "$verification" "$gateway_url" "$verification_evidence" 180
  completed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  rto_seconds=$(($(date +%s) - started_epoch))
  rpo_seconds=$(jq -nr \
    --arg recovery "$(jq -r '.recoveryPointAt' "$manifest")" \
    --arg acknowledged "$(jq -r '.lastAcknowledgedWriteAt' "$manifest")" \
    '($recovery | fromdateiso8601) - ($acknowledged | fromdateiso8601)')
  mkdir -p "$(dirname "$report")"
  jq -n \
    --arg artifactId "$artifact_id" \
    --arg sourceCommit "$(jq -r '.source.commit' "$manifest")" \
    --arg datasetId "$(jq -r '.verification.datasetId' "$manifest")" \
    --arg destinationProject "$project" \
    --arg startedAt "$started_at" \
    --arg completedAt "$completed_at" \
    --arg recoveryPointAt "$(jq -r '.recoveryPointAt' "$manifest")" \
    --argjson recoveryPointSeconds "$rpo_seconds" \
    --argjson recoveryTimeSeconds "$rto_seconds" \
    --arg topologyEpoch "$restored_epoch" \
    --argjson topologyVersion "$restored_version" \
    --slurpfile verification "$verification_evidence" \
    '{schemaVersion:1,status:"passed",artifactId:$artifactId,sourceCommit:$sourceCommit,
      datasetId:$datasetId,destinationProject:$destinationProject,startedAt:$startedAt,
      completedAt:$completedAt,recoveryPointAt:$recoveryPointAt,
      recoveryPointSeconds:$recoveryPointSeconds,recoveryTimeSeconds:$recoveryTimeSeconds,
      topology:{epoch:$topologyEpoch,version:$topologyVersion},verification:$verification[0]}' \
    >"$report"
  rm -f "$verification_evidence"
  log "Restore verified through the public gateway; report: $report"
  publish_recovery_metric restore_success "$(date +%s)"
  jq -n --arg artifactId "$artifact_id" --arg project "$project" --arg report "$report" \
    --argjson recoveryPointSeconds "$rpo_seconds" --argjson recoveryTimeSeconds "$rto_seconds" \
    '{status:"success",artifactId:$artifactId,project:$project,report:$report,
      recoveryPointSeconds:$recoveryPointSeconds,recoveryTimeSeconds:$recoveryTimeSeconds}'
}

verify_command() {
  local verification= gateway_url=
  while (($#)); do
    case "$1" in
      --verification) verification=${2:?}; shift 2 ;;
      --gateway-url) gateway_url=${2:?}; shift 2 ;;
      *) fail "unknown verify option: $1"; usage; return 2 ;;
    esac
  done
  [[ -n "$verification" ]] || fail 'verify requires --verification'
  require_command jq
  require_command curl
  validate_verification_spec "$verification"
  gateway_url=${gateway_url:-$(jq -r '.gatewayUrl // "http://localhost:19080"' "$verification")}
  verify_gateway "$verification" "$gateway_url" '' 180
  jq -n --arg datasetId "$(jq -r '.datasetId' "$verification")" \
    '{status:"verified",datasetId:$datasetId}'
}

main() {
  (($# > 0)) || {
    usage
    return 2
  }
  local command=$1
  shift
  case "$command" in
    snapshot) snapshot_command "$@" ;;
    validate) validate_command "$@" ;;
    restore) restore_command "$@" ;;
    verify) verify_command "$@" ;;
    -h|--help|help) usage ;;
    *) fail "unknown command: $command"; usage; return 2 ;;
  esac
}

main "$@"
