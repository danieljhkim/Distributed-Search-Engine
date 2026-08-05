#!/usr/bin/env sh
set -eu

. "$(dirname "$0")/_lib.sh"

curl -sS "http://localhost:8080/actuator/prometheus"
