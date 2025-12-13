#!/usr/bin/env sh
set -eu

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
POLICY_DIR="$ROOT_DIR/.box/policies"
ACTIVE="$ROOT_DIR/.box/policies.yaml"

usage() {
  echo "Usage:"
  echo "  devbox policy show"
  echo "  devbox policy set <readonly|safe-write|admin>"
}

cmd="${1:-}"
arg="${2:-}"

case "$cmd" in
  show)
    if [ -f "$ACTIVE" ]; then
      echo "Active policy: $ACTIVE"
      awk '/^mode:/{print "mode:", $2}' "$ACTIVE" | tr -d '"'
    else
      echo "No active policy found at $ACTIVE"
      exit 1
    fi
    ;;
  set)
    [ -n "${arg:-}" ] || { usage; exit 1; }
    src="$POLICY_DIR/$arg.yaml"
    [ -f "$src" ] || { echo "Missing policy profile: $src"; exit 1; }
    cp "$src" "$ACTIVE"
    echo "Switched policy -> $arg"
    ;;
  *)
    usage
    exit 1
    ;;
esac