#!/usr/bin/env sh
set -eu

. "$(dirname "$0")/_lib.sh"

ensure_dirs
log "Resetting DevBox state..."
rm -rf "$STATE_DIR"/* 2>/dev/null || true
rm -rf "$REPORT_DIR"/* 2>/dev/null || true
log "Reset complete. (Logs preserved in logs/)"
