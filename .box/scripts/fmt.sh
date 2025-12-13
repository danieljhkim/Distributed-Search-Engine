#!/usr/bin/env sh
set -eu

. "$(dirname "$0")/_lib.sh"

ensure_dirs
log "Fmt: optional. Implement per repo; default is noop."
exit 0
