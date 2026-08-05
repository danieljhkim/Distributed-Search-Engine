#!/usr/bin/env bash
set -euo pipefail

# Optional: set REPO to "owner/name" if you're not running inside the repo dir.
# Example: REPO="danieljhkim/dsearch"
REPO="danieljhkim/dsearch"

gh_repo_args=()
if [[ -n "${REPO}" ]]; then
  gh_repo_args=(-R "${REPO}")
fi

# name|color|description
labels=(
  "dependencies|0366d6|Dependency updates (Dependabot, Renovate, etc.)"
  "github-actions|000000|GitHub Actions workflows and CI tooling"
  "java|b07219|Java-related changes"
  "maven|c71a36|Maven dependencies/plugins/build changes"
  "docker|2496ed|Dockerfiles, images, and container-related changes"
  "security|d73a4a|Security-related changes or vulnerability fixes"
)

for entry in "${labels[@]}"; do
  IFS="|" read -r name color desc <<< "${entry}"

  echo "Ensuring label: ${name}"
  if gh label create "${name}" "${gh_repo_args[@]}" --color "${color}" --description "${desc}" >/dev/null 2>&1; then
    echo "  created"
  else
    # If it already exists, update it to the desired color/description.
    gh label edit "${name}" "${gh_repo_args[@]}" --color "${color}" --description "${desc}" >/dev/null
    echo "  updated"
  fi
done

echo "Done."