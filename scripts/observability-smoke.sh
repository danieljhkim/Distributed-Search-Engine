#!/usr/bin/env bash
set -Eeuo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
rules="$repo_root/observability/prometheus/alerts.yml"
fixture="$repo_root/observability/fixtures/healthy.prom"

command -v promtool >/dev/null || {
  printf 'promtool is required (install Prometheus to run this smoke test)\n' >&2
  exit 2
}

promtool check rules "$rules"

# A bounded representative scrape is part of the contract: it has no request, tenant, document,
# partition, topology epoch, or artifact identifiers. Degraded and overloaded cases are generated
# by changing only closed-enum values, so alert expressions can be evaluated safely in CI.
for required_metric in \
  http_server_requests_seconds_count \
  dsearch_search_fanout_outcomes_total \
  dsearch_embedding_inference_outcomes_total \
  dsearch_topology_members \
  dsearch_embedding_model_ready \
  dsearch_lucene_commit_outcomes_total \
  dsearch_lucene_disk_available_bytes \
  dsearch_snapshot_last_successful_timestamp_seconds \
  dsearch_restore_outcomes_total; do
  grep -q "^${required_metric}" "$fixture" || {
    printf 'missing required signal: %s\n' "$required_metric" >&2
    exit 1
  }
done

grep -q 'outcome="success"' "$fixture"
grep -q 'outcome="rejected"' "$fixture"
printf 'observability smoke fixture and alert rules are valid\n'
