#!/usr/bin/env bash

set -Eeuo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
recovery="$repo_root/scripts/dsearch-recovery.sh"
config="$repo_root/dk.common/src/main/resources/app-config.docker.yaml"
pom="$repo_root/pom.xml"
scratch=$(mktemp -d "${TMPDIR:-/tmp}/dsearch-recovery-contract.XXXXXX")
trap 'rm -rf "$scratch"' EXIT

fail() {
  printf '[recovery-contract] ERROR: %s\n' "$*" >&2
  exit 1
}

sha256_file() {
  if command -v sha256sum >/dev/null; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

file_size() {
  if stat -c '%s' "$1" >/dev/null 2>&1; then
    stat -c '%s' "$1"
  else
    stat -f '%z' "$1"
  fi
}

expect_failure() {
  local description=$1
  shift
  if "$@" >"$scratch/unexpected.stdout" 2>"$scratch/expected.stderr"; then
    fail "$description unexpectedly succeeded"
  fi
}

artifact="$scratch/valid-snapshot"
mkdir -p \
  "$artifact/metadata" \
  "$artifact/payload/coordinator" \
  "$artifact/payload/index-nodes/index-node-0/shard-fixture" \
  "$artifact/payload/model-caches/index-node-0"
cp "$config" "$artifact/metadata/app-config.yaml"
cp "$pom" "$artifact/metadata/pom.xml"
git -C "$repo_root" rev-parse HEAD >"$artifact/metadata/source-commit.txt"
: >"$artifact/metadata/source-status.txt"
printf '%s\n' 'fixture-lucene-segment' \
  >"$artifact/payload/index-nodes/index-node-0/shard-fixture/segments_1"
printf '%s\n' 'fixture-model-metadata' \
  >"$artifact/payload/model-caches/index-node-0/model.properties"
printf '%s\n' \
  'state.format.version=1' \
  'topology.epoch=fixture-epoch' \
  'topology.version=7' \
  >"$artifact/payload/coordinator/coordinator-topology.properties"
jq -n '{schemaVersion:1,datasetId:"manifest-contract-fixture",
  gatewayUrl:"http://localhost:19080",lastAcknowledgedWriteAt:"2026-08-30T00:00:00Z",
  documentCount:{partitionId:"fixture",query:"recoveryset",expected:1},
  queries:[
    {name:"bm25",partitionId:"fixture",query:"lucene",searchType:"BM25",expectedDocIds:["doc-1"]},
    {name:"vector",partitionId:"fixture",query:"semantic",searchType:"SEMANTIC",expectedDocIds:["doc-1"]},
    {name:"hybrid",partitionId:"fixture",query:"hybrid",searchType:"HYBRID",expectedDocIds:["doc-1"]}
  ]}' >"$artifact/metadata/verification.json"
jq -n '{verifiedAt:"2026-08-30T00:00:00Z",checks:[]}' \
  >"$artifact/metadata/pre-snapshot-verification.json"

inventory="$scratch/inventory.jsonl"
: >"$inventory"
while IFS= read -r -d '' file; do
  relative=${file#"$artifact/"}
  jq -nc \
    --arg path "$relative" \
    --arg sha256 "$(sha256_file "$file")" \
    --argjson size "$(file_size "$file")" \
    '{path:$path,size:$size,sha256:$sha256}' >>"$inventory"
done < <(find "$artifact" -type f -print0 | LC_ALL=C sort -z)

revision=$(sed -n 's:.*<revision>\([^<]*\)</revision>.*:\1:p' "$pom" | head -n 1)
lucene_version=$(sed -n 's:.*<lucene.version>\([^<]*\)</lucene.version>.*:\1:p' "$pom" | head -n 1)
jq -n \
  --arg configSha256 "$(sha256_file "$config")" \
  --arg pomSha256 "$(sha256_file "$pom")" \
  --arg mavenRevision "$revision" \
  --arg luceneVersion "$lucene_version" \
  --arg commit "$(git -C "$repo_root" rev-parse HEAD)" \
  --slurpfile files "$inventory" \
  '{schemaVersion:1,state:"complete",artifactId:"fixture-artifact",
    createdAt:"2026-08-30T00:00:01Z",recoveryPointAt:"2026-08-30T00:00:01Z",
    lastAcknowledgedWriteAt:"2026-08-30T00:00:00Z",
    source:{project:"fixture-source",commit:$commit,repositoryDirty:false},
    compatibility:{configSha256:$configSha256,pomSha256:$pomSha256,
      mavenRevision:$mavenRevision,luceneVersion:$luceneVersion,coordinatorStateFormat:1},
    topology:{epoch:"fixture-epoch",version:7,indexServices:["index-node-0"],queryServices:[]},
    verification:{datasetId:"manifest-contract-fixture",specification:"metadata/verification.json",
      preSnapshotEvidence:"metadata/pre-snapshot-verification.json"},
    files:$files}' >"$artifact/manifest.json"

"$recovery" validate --snapshot "$artifact" >/dev/null

expect_failure 'missing artifact' "$recovery" validate --snapshot "$scratch/missing"

partial="$scratch/fixture.partial.interrupted"
cp -R "$artifact" "$partial"
expect_failure 'interrupted snapshot' "$recovery" validate --snapshot "$partial"

checksum_target="$artifact/payload/index-nodes/index-node-0/shard-fixture/segments_1"
checksum_backup="$scratch/segments.backup"
cp "$checksum_target" "$checksum_backup"
printf '%s\n' 'corruption' >>"$checksum_target"
expect_failure 'checksum mismatch' "$recovery" validate --snapshot "$artifact"
mv "$checksum_backup" "$checksum_target"
"$recovery" validate --snapshot "$artifact" >/dev/null

incompatible="$scratch/incompatible-config.yaml"
cp "$config" "$incompatible"
printf '%s\n' '# incompatible schema/model metadata' >>"$incompatible"
expect_failure 'incompatible metadata' "$recovery" validate --snapshot "$artifact" --config "$incompatible"

rm "$artifact/payload/model-caches/index-node-0/model.properties"
expect_failure 'missing checksummed payload' "$recovery" validate --snapshot "$artifact"

printf '[recovery-contract] Manifest refusal checks passed\n'
