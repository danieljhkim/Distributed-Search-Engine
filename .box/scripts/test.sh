#!/usr/bin/env sh
set -eu

. "$(dirname "$0")/_lib.sh"

ensure_dirs
log "Test: starting project-specific validation"

log "Resolved test command: ${BOX_TEST_CMD:-<none>}"

if [ "${BOX_TEST_CMD:-}" = "" ]; then
  log "ERROR: BOX_TEST_CMD is not set."
  log "Set it in .box/box.yaml (runtime.env.BOX_TEST_CMD) or export it in your shell."
  exit 1
fi

# Support forwarding args via `devbox test -- ...`
if [ "${1:-}" = "--" ]; then
    shift 1
fi

# Default query; can be overridden with ARGS
QUERY=${1:-"neural networks"}
MODE=${2:-LEXICAL}
FROM=${3:-0}
SIZE=${4:-5}
SHARD=${5:-}

SAFE_QUERY=$(printf "%s" "$QUERY" | tr ' ' '_' | tr -cd '[:alnum:]_-.')
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
OUT_FILE="$REPORT_DIR/search_${SAFE_QUERY}_${MODE}_${FROM}_${SIZE}_${TIMESTAMP}.json"

CMD="$BOX_TEST_CMD \"$QUERY\" $MODE $FROM $SIZE \"$SHARD\" > \"$OUT_FILE\""

run_sh_logged "box-test" "$CMD"

log "Test: complete. Output -> $OUT_FILE"
