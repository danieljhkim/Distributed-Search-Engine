#!/bin/bash

echo "Stopping Distributed Search Engine cluster..."

# Grep patterns for each module
PATTERNS=(
  "coordinator-1.0-SNAPSHOT.jar"
  "index-node-1.0-SNAPSHOT.jar"
  "query-node-1.0-SNAPSHOT.jar"
  "gateway-1.0-SNAPSHOT.jar"
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