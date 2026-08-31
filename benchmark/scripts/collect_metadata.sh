#!/usr/bin/env bash
set -euo pipefail

BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATASET="${DSEARCH_BENCH_DATASET:-$BENCH_DIR/datasets/ci-smoke.jsonl}"
source_commit="$(git -C "$BENCH_DIR/.." rev-parse HEAD)"
config_sha="$(shasum -a 256 "$BENCH_DIR/config/scenarios.yaml" "$BENCH_DIR/config/query-mixes.json" | shasum -a 256 | awk '{print $1}')"
dataset_sha="$(shasum -a 256 "$DATASET" | awk '{print $1}')"
dataset_documents="$(grep -cve '^[[:space:]]*$' "$DATASET")"
manifest_path="${DSEARCH_BENCH_DATASET_MANIFEST:-${DATASET%.jsonl}.manifest.json}"
if [[ -f "$manifest_path" ]]; then
  manifest_sha="$(shasum -a 256 "$manifest_path" | awk '{print $1}')"
else
  manifest_path=none
  manifest_sha=none
fi
jvm="$(java -version 2>&1 | tr '\n' ' ' || true)"
hardware="$(uname -a)"

jq -n \
  --arg sourceCommit "$source_commit" \
  --arg imageDigests "${DSEARCH_IMAGE_DIGESTS:-unknown}" \
  --arg configurationSha256 "$config_sha" \
  --arg embeddingModel "${DSEARCH_EMBEDDING_MODEL:-unknown}" \
  --arg datasetPath "$DATASET" \
  --arg datasetSha256 "$dataset_sha" \
  --argjson datasetDocuments "$dataset_documents" \
  --arg datasetManifest "$manifest_path" \
  --arg datasetManifestSha256 "$manifest_sha" \
  --arg warmup "${DSEARCH_BENCH_WARMUP:-5s}" \
  --arg duration "${DSEARCH_BENCH_DURATION:-10s}" \
  --argjson concurrency "${DSEARCH_BENCH_VUS:-2}" \
  --arg hardware "$hardware" \
  --arg jvm "$jvm" \
  --arg profile "${DSEARCH_BENCH_PROFILE:-ci-smoke}" \
  --arg label "${DSEARCH_RUN_LABEL:-local}" \
  '{schemaVersion:1, sourceCommit:$sourceCommit, imageDigests:$imageDigests, configurationSha256:$configurationSha256, embeddingModel:$embeddingModel, dataset:{path:$datasetPath,sha256:$datasetSha256,documents:$datasetDocuments,manifest:$datasetManifest,manifestSha256:$datasetManifestSha256}, warmup:$warmup, duration:$duration, concurrency:$concurrency, hardware:$hardware, jvm:$jvm, profile:$profile, label:$label}'
