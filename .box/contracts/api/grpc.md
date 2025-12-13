# gRPC API (dsearch)

This document describes the **gRPC service contracts** used inside dsearch. It is a human-readable guide that complements the canonical protobuf definitions in:

- `../schemas/proto/`

If this document and the `.proto` files disagree, the **.proto files win**.

---

## Overview

dsearch is composed of multiple internal services that communicate primarily over gRPC:

- **Coordinator** — cluster membership and shard topology
- **Index Node** — indexing and shard-local search execution
- **Query Node** — fan-out search across shards and result merging
- **Gateway** — HTTP entrypoint; typically talks to Query Nodes (and sometimes Coordinator)

The exact RPC shape is defined in the `.proto` files. This document focuses on usage conventions.

---

## Service Names and Identity

Use stable service names for logging and observability:

- `dsearch-coordinator`
- `dsearch-index-node`
- `dsearch-query-node`

Each node SHOULD expose a stable logical `nodeId` (e.g. `index-0`, `query-1`) in logs and (where applicable) RPC metadata.

---

## Endpoints (Local DevBox)

These endpoints reflect the default DevBox local topology:

- Coordinator: `localhost:7000` (gRPC)
- Index Nodes: `localhost:5000..5001` (gRPC)
- Query Nodes: `localhost:6000..6001` (gRPC)

Health endpoints (HTTP) are documented separately under:
- `../../observability/health.md`

---

## Metadata and Correlation

All request-scoped RPCs SHOULD carry a correlation id.

### Header (gRPC metadata)
Use:
- `x-correlation-id`

The gateway SHOULD generate a correlation id if one is not present, and propagate it downstream.

All services SHOULD include the correlation id in request-scoped logs.

---

## Common RPC Patterns

### Coordinator patterns
Typical coordinator interactions include:

- Node registration / heartbeat
- Shard topology queries
- Cluster membership reads

Clients SHOULD treat coordinator calls as control-plane operations:
- use retries with backoff
- use conservative timeouts
- avoid hot loops

### Index Node patterns
Index nodes are the data plane and typically support:

- Index document
- Delete document
- Search shard (lexical/semantic/hybrid)

Requests SHOULD be idempotent where possible:
- index by `docId` is overwrite-idempotent
- delete by `docId` is idempotent

### Query Node patterns
Query nodes typically:

- fan out search requests to relevant shards/index nodes
- merge and re-rank results
- return a single ranked list

Query nodes SHOULD provide deterministic ordering with a stable tie-breaker (see `conventions.md`).

---

## Timeouts, Retries, and Error Handling

### Timeouts
- Control-plane (Coordinator) RPCs: short timeouts, retryable
- Data-plane (Index/Search) RPCs: slightly longer timeouts, limited retries

### Retries
Retries SHOULD:
- preserve the same correlation id
- use bounded retry counts
- avoid retrying non-transient errors

### Errors
Services SHOULD return:
- meaningful gRPC status codes (`INVALID_ARGUMENT`, `NOT_FOUND`, `UNAVAILABLE`, `INTERNAL`)
- structured error details where possible (optional)

Do not rely on string-matching error messages.

---

## Tools (Optional)

If you use `grpcurl` for local testing, a typical pattern is:

```bash
grpcurl -plaintext -H 'x-correlation-id: demo-1' localhost:7000 list
```

For method invocation, follow your `.proto` package/service names.

---

## Compatibility

Breaking changes include:
- removing RPC methods
- changing request/response field meaning
- changing field types or tags
- changing service names or package names

Non-breaking changes include:
- adding new RPC methods
- adding new optional fields

Prefer additive evolution. If a breaking change is required, version the service/package.

---
