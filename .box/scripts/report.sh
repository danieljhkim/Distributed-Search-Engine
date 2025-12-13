#!/usr/bin/env sh
set -eu

. "$(dirname "$0")/_lib.sh"

ensure_dirs
log "Reports summary:"

# Print report files relative to repo root (best-effort).
if command -v find >/dev/null 2>&1; then
  find "$REPORT_DIR" -maxdepth 2 -type f 2>/dev/null | sed "s|^$ROOT_DIR/||" || true
else
  log "WARN: find not available; cannot list reports."
fi
