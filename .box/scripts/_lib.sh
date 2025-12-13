#!/usr/bin/env sh
set -eu

# Load local DevBox environment overrides if present
# This allows BOX_UP_CMD / BOX_DOWN_CMD to be defined without manual export
if [ -f ".box/env/.env.local" ]; then
  # shellcheck disable=SC1091
  . ".box/env/.env.local"
fi

# Shared helpers for DevBox scripts.
# Keep this POSIX-sh compatible; use bash only when we need pipefail.

BOX_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"

LOG_DIR="$ROOT_DIR/logs"
REPORT_DIR="$BOX_DIR/reports"
STATE_DIR="$BOX_DIR/state"

timestamp() { date -u +"%Y-%m-%dT%H:%M:%SZ"; }
log() { printf "%s %s\n" "$(timestamp)" "$*"; }

ensure_dirs() {
  mkdir -p "$LOG_DIR" "$REPORT_DIR" "$STATE_DIR"
}

require_cmd() {
  cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    log "Missing: $cmd"
    return 1
  fi
  return 0
}

# Run a shell string and tee output to logs/<name>.log.
# IMPORTANT: uses bash + pipefail so failures are not masked by tee.
run_sh_logged() {
  name="$1"; shift
  script="$1"; shift || true

  ensure_dirs

  outfile="$LOG_DIR/$name.log"
  log "Running: $script"
  require_cmd bash

  # Use bash for pipefail; preserve exit code even with tee.
  bash -lc "set -o pipefail; $script 2>&1 | tee \"$outfile\""
}

# Run an argv command and tee output to logs/<name>.log.
# NOTE: In POSIX sh, piping through tee can mask exit codes; prefer run_sh_logged when possible.
run_argv_logged() {
  name="$1"; shift
  ensure_dirs

  outfile="$LOG_DIR/$name.log"
  log "Running: $*"
  ( "$@" ) 2>&1 | tee "$outfile"
}
