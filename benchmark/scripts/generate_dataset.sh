#!/usr/bin/env bash
set -euo pipefail

output="${1:?output JSONL path is required}"
documents="${2:?document count is required}"
[[ "$documents" =~ ^[1-9][0-9]*$ ]] || { echo "document count must be a positive integer" >&2; exit 2; }
mkdir -p "$(dirname "$output")"
: > "$output"
for i in $(seq 1 "$documents"); do
  printf '{"id":"capacity-%s","partitionId":"bench","fields":{"title":"Capacity document %s","content":"lucene vector relevance benchmark document %s"}}\n' "$i" "$i" "$i" >> "$output"
done
manifest="${output%.jsonl}.manifest.json"
jq -n --arg dataFile "$(basename "$output")" --arg dataSha256 "$(shasum -a 256 "$output" | awk '{print $1}')" --argjson documentCount "$documents" \
  '{schemaVersion:1,name:"generated-capacity",purpose:"capacity evidence dataset",documentCount:$documentCount,dataFile:$dataFile,dataSha256:$dataSha256}' > "$manifest"
printf '%s documents written to %s\n' "$documents" "$output"
