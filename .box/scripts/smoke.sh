#!/usr/bin/env sh
set -eu

. "$(dirname "$0")/_lib.sh"

MODE="${1:-}"

TIMEOUT_SECS="${BOX_HEALTH_TIMEOUT_SECS:-2}"
RETRIES="${BOX_HEALTH_RETRIES:-20}"
SLEEP_SECS="${BOX_HEALTH_SLEEP_SECS:-1}"

DEFAULT_URLS="
http://localhost:8080/actuator/health
http://localhost:8080/health
"

if [ "${BOX_HEALTH_URL:-}" != "" ]; then
  URLS="$BOX_HEALTH_URL"
else
  URLS="$DEFAULT_URLS"
fi

curl_ok() {
  url="$1"
  curl -fsS --max-time "$TIMEOUT_SECS" "$url" >/dev/null 2>&1
}

health_check_with_retries() {
  name="$1"

  if ! command -v curl >/dev/null 2>&1; then
    echo "HEALTH $name FAIL (curl_missing)"
    return 1
  fi

  attempt=1
  while [ "$attempt" -le "$RETRIES" ]; do
    for url in $URLS; do
      if curl_ok "$url"; then
        echo "HEALTH $name OK $url"
        return 0
      fi
    done

    if [ "$attempt" -eq "$RETRIES" ]; then
      first_url="$(printf "%s\n" $URLS | head -n 1)"
      echo "HEALTH $name FAIL $first_url"
      return 1
    fi

    sleep "$SLEEP_SECS"
    attempt=$((attempt + 1))
  done

  first_url="$(printf "%s\n" $URLS | head -n 1)"
  echo "HEALTH $name FAIL $first_url"
  return 1
}

ensure_dirs

if [ "$MODE" = "--health" ]; then
  log "Health: running checks (retries=$RETRIES sleep=${SLEEP_SECS}s timeout=${TIMEOUT_SECS}s)..."
  health_check_with_retries "service"
  exit $?
fi

log "Smoke: running quick checks..."
health_check_with_retries "service" || true
exit 0
