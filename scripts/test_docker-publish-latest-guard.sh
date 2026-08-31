#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
source "$repo_root/scripts/docker-publish-latest-guard.sh"

assert_equals() {
  local expected=$1
  local actual=$2
  local description=$3

  if [[ "$expected" != "$actual" ]]; then
    echo "$description: expected $expected, got $actual" >&2
    exit 1
  fi
}

newest=$(printf '%s\n' \
  v1.9.0 \
  v1.10.0-rc.1 \
  v1.10.0 \
  v1.11.0-RC2 \
  v1.2.0 | latest_eligible_release_tag)
assert_equals v1.10.0 "$newest" "RC tags must not become latest candidates"

if release_tag_may_promote_latest v1.9.0 "$newest"; then
  echo "older release rerun was allowed to roll latest back" >&2
  exit 1
fi

if ! release_tag_may_promote_latest v1.10.0 "$newest"; then
  echo "newest eligible release was prevented from promoting latest" >&2
  exit 1
fi

workflow=$repo_root/.github/workflows/docker-publish.yml
grep -Fq 'group: docker-latest-promotion-${{ github.repository }}' "$workflow"
grep -Fq 'scripts/docker-publish-latest-guard.sh' "$workflow"
