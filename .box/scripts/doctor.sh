#!/usr/bin/env sh
set -eu

. "$(dirname "$0")/_lib.sh"

log "Doctor: checking environment..."
ensure_dirs

# Core tools
require_cmd git
require_cmd curl
require_cmd bash

# Config sanity (these are required for DevBox option-B flows)
if [ "${BOX_UP_CMD:-}" = "" ]; then
  log "WARN: BOX_UP_CMD is not set (up.sh will fail). Configure it in .box/box.yaml or export it."
fi

if [ "${BOX_DOWN_CMD:-}" = "" ]; then
  log "WARN: BOX_DOWN_CMD is not set (down.sh will fail). Configure it in .box/box.yaml or export it."
fi

# Optional: port conflict hint (best-effort)
if command -v lsof >/dev/null 2>&1; then
  # If user configured BOX_HEALTH_URL, try to extract the port from it; otherwise, default to 8080.
  health_url="${BOX_HEALTH_URL:-http://localhost:8080/health}"
  port="$(printf "%s" "$health_url" | sed -n 's#.*://[^:/]*:\([0-9][0-9]*\).*#\1#p')"
  if [ "$port" = "" ]; then port="8080"; fi

  if lsof -i ":$port" >/dev/null 2>&1; then
    log "WARN: Port $port appears in use (may conflict with local runtime). Update BOX_HEALTH_URL/ports if needed."
  fi
fi

log "Doctor: OK"
