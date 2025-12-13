#!/usr/bin/env sh
set -eu

. "$(dirname "$0")/_lib.sh"

# DevBox: normalized "down" command.
# Responsibility:
#   - Stop the local runtime via BOX_DOWN_CMD
#   - Stream all output to logs/box-down.log
#   - Fail fast if anything goes wrong (agent-safe)

log "Down: stopping local runtime..."
ensure_dirs

log "Resolved stop command: ${BOX_DOWN_CMD:-<none>}"

if [ "${BOX_DOWN_CMD:-}" = "" ]; then
  log "ERROR: BOX_DOWN_CMD is not set."
  log "Set it in .box/box.yaml (runtime.env.BOX_DOWN_CMD) or export it in your shell."
  exit 1
fi

run_sh_logged "box-down" "$BOX_DOWN_CMD"

log "Down: complete."
