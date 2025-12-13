#!/usr/bin/env sh
set -eu

. "$(dirname "$0")/_lib.sh"

# DevBox: normalized "up" command.
# Responsibility:
#   - Start the local runtime via BOX_UP_CMD
#   - Stream all output to logs/box-up.log
#   - Fail fast if anything goes wrong (agent-safe)

log "Up: starting local runtime..."
ensure_dirs

log "Resolved start command: ${BOX_UP_CMD:-<none>}"

if [ "${BOX_UP_CMD:-}" = "" ]; then
  log "ERROR: BOX_UP_CMD is not set."
  log "Set it in .box/box.yaml (runtime.env.BOX_UP_CMD) or export it in your shell."
  exit 1
fi

run_sh_logged "box-up" "$BOX_UP_CMD"

log "Up: complete."
