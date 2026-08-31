#!/usr/bin/env bash

# Select the highest non-RC release tag from stdin. Input is one tag name per
# line, as produced by `git ls-remote --tags --refs origin 'v*.*.*'` after
# removing the refs/tags/ prefix.
latest_eligible_release_tag() {
  local tag version

  while IFS= read -r tag; do
    version=${tag#v}
    [[ "$tag" != "$version" && -n "$version" && "$version" != */* ]] || continue
    [[ "$version" =~ (^|[-.])[Rr][Cc]([.-]?[0-9].*)?$ ]] && continue
    printf '%s\n' "$tag"
  done | LC_ALL=C sort -V | tail -n 1
}

release_tag_may_promote_latest() {
  local candidate=$1
  local newest_eligible=$2

  [[ -n "$newest_eligible" && "$candidate" == "$newest_eligible" ]]
}
