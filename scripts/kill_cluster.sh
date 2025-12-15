#!/bin/bash

echo "Stopping Distributed Search Engine cluster..."

# Grep patterns for each module
PATTERNS=(
  "dk.coordinator*.jar"
  "dk.index-node*.jar"
  "dk.query-node*.jar"
  "dk.gateway*.jar"
)

for pattern in "${PATTERNS[@]}"; do
  PIDS=$(pgrep -f "$pattern")
  if [[ -n "$PIDS" ]]; then
    echo "Killing processes for: $pattern (PIDs: $PIDS)"
    kill $PIDS 2>/dev/null || true
  else
    echo "No running processes for: $pattern"
  fi
done

echo "Done."