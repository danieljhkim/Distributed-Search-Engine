# Production observability contract

This document defines the production SLI contract. All objectives use a rolling 30-day
window; alert rules use the shorter windows shown below so that an operator can intervene
before the monthly error budget is exhausted.

| User outcome | SLI and objective | Measurement |
| --- | --- | --- |
| Search availability | 99.9% of admitted search requests finish with an explicit success or partial result inside the 3 s request budget | Gateway `http_server_requests_seconds_*`, `dsearch_search_fanout_outcomes_total` |
| Mutation durability | 99.9% of accepted mutations receive a durable Lucene acknowledgement; no successful acknowledgement follows a failed commit | `dsearch_lucene_commit_outcomes_total`, `dsearch_lucene_last_successful_commit_timestamp_seconds` |
| Search latency | 99% of successful gateway searches complete within 1 s | Gateway `http_server_requests_seconds_bucket` |
| Recovery | most recent valid snapshot is younger than 24 h; a restore success is recorded for every drill | recovery textfile metrics described below |
| Replica convergence | every configured copy is eligible only after manifest equality; repair traffic remains within configured bounds | `dsearch_replica_repair_outcomes_total`, `dsearch_replica_repair_duration_seconds`, `dsearch_replica_repairs_active`, `dsearch_replica_repairs_remaining` |

Document cardinality is read from committed Lucene state through `GET /api/v1/index/count`, not
from gateway mutation counters. Operators must treat a non-empty unavailable or failed logical
shard list as a partial observation rather than an empty shard or a complete zero count.

## Bounded dimensions

Metrics must never include document ids, partition ids, query text, hostnames supplied by a
request, request ids, or model URLs. The only application labels are closed enums: `outcome`
(`success`, `partial_failure`, `deadline_exhausted`, `failed`, `rejected`), topology `role`
(`index`, `query`, `coordinator`), topology `state` (`total`, `healthy`), gRPC protobuf service
and method names, and gRPC status codes. Gateway partition latency is capped at 100 validated
values plus `__overflow__`; the controller test prevents a request stream from creating an
unbounded series. Replica repair metrics also use only the bounded `operation` and `outcome`
dimensions; repair, shard, and node identifiers are carried in the operator RPC instead of labels.

## Signals and operator actions

The versioned rules in `observability/prometheus/alerts.yml` cover gateway error and latency
budget burn, rejected admission, fan-out deadline/failure, topology health loss, model
readiness and saturation, commit failure/staleness, disk capacity, snapshot age, and restore
failure. Every alert includes a runbook URL and is tested by the companion promtool fixture.

`scripts/dsearch-recovery.sh` must publish the snapshot and restore timestamps to the configured
Prometheus textfile collector after a successful recovery operation. The collector file is
`dsearch_recovery.prom`; it has only timestamp and bounded `outcome` values, never an artifact
id or project name.

Run the local smoke test with `scripts/observability-smoke.sh`. It loads representative healthy,
degraded, and overloaded samples and evaluates every alert expression using `promtool`.
