#!/usr/bin/env sh
set -eu

. "$(dirname "$0")/_lib.sh"

ensure_dirs

TAIL="${1:-200}"
PATTERN="${2:-*.log}"

log "Tailing logs (last $TAIL lines each) matching: $PATTERN"

# best-effort
for f in "$LOG_DIR"/$PATTERN; do
  [ -f "$f" ] || continue
  echo "---- $f ----"
  tail -n "$TAIL" "$f" || true
done
