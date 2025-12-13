# Logging Convention

This document defines the **logging conventions** for the dsearch system when running locally under DevBox.

Logs are the **primary machine-readable signal** for diagnosis and iteration.
They must be stable, structured, and discoverable.

---

## Log Locations

Each service MUST write logs to the `logs/` directory at the repo root.

Recommended files:

- Gateway: `logs/gateway.log`
- Coordinator: `logs/coordinator.log`
- Query Node:
  - `logs/query-0.log`
  - `logs/query-1.log`
- Index Node:
  - `logs/index-0.log`
  - `logs/index-1.log`

Log file names should remain stable across runs so tools and agents can locate them deterministically.

---

## Log Format

Structured logs are **strongly preferred**.

### Recommended JSON format

```json
{
  "ts": "2025-01-01T12:00:00.123Z",
  "level": "INFO",
  "service": "query-node",
  "nodeId": "query-0",
  "shardId": "shard-1",
  "msg": "Search request completed",
  "correlationId": "a8f3c9e2",
  "durationMs": 12
}
```

### Required fields

- `ts` — ISO-8601 timestamp (UTC)
- `level` — log level (`DEBUG`, `INFO`, `WARN`, `ERROR`)
- `service` — logical service name
- `msg` — human-readable message

### Strongly recommended fields

- `nodeId` — node identifier (e.g. `query-0`)
- `shardId` — shard identifier (if applicable)
- `correlationId` — request/trace correlation
- `durationMs` — latency for completed operations

---

## Log Levels

Use log levels consistently:

- `DEBUG` — detailed internal state (off by default)
- `INFO` — normal operation and lifecycle events
- `WARN` — unexpected but recoverable conditions
- `ERROR` — failed operations or invariants violated

Do not log stack traces at `INFO` level.

---

## Correlation & Requests

All request-scoped logs SHOULD include a `correlationId`.

- Generate at the gateway if not present
- Propagate across:
  - gateway → query nodes → index nodes
- Reuse the same ID across retries where possible

This enables agents to reconstruct request flow using logs alone.

---

## Startup & Lifecycle Logs

Each service MUST log:

- startup completion (listening ports, nodeId)
- shutdown initiation
- abnormal termination

Example:

```json
{
  "ts": "2025-01-01T12:00:02.000Z",
  "level": "INFO",
  "service": "gateway",
  "msg": "Gateway started",
  "port": 8080
}
```

---

## Error Logging

For errors:

- Log the failure once at the point of origin
- Include:
  - error message
  - error type
  - correlationId (if request-scoped)
- Avoid duplicate logging across layers

Example:

```json
{
  "ts": "2025-01-01T12:00:05.456Z",
  "level": "ERROR",
  "service": "index-node",
  "nodeId": "index-1",
  "msg": "Failed to load shard",
  "shardId": "shard-3",
  "error": "IndexNotFoundException"
}
```

---

## Log Volume & Safety

- Logs must be append-only
- Avoid unbounded per-request logging
- Do not log:
  - full document contents
  - raw query strings at INFO level
  - secrets or credentials

Agents rely on logs being readable and bounded.

---

## DevBox Interaction

DevBox treats logs as a **first-class signal**.

DevBox responsibilities:
- assume logs live under `logs/`
- allow bounded read access via `devbox logs` or MCP tools
- not interpret log contents

Agents use logs for diagnosis, not control flow.

---

## Summary

Logging in dsearch is:
- Mandatory
- Structured-first
- Correlation-aware
- Stable across runs
- Safe for machine consumption

If a failure is not observable via logs, it is considered a logging defect.
