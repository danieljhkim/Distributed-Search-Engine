

# API Conventions (dsearch)

This document describes **API-level conventions** for dsearch. It complements the machine-readable schemas in:

- `../schemas/openapi.yaml` (HTTP Gateway API)
- `../schemas/proto/` (gRPC services)
- `../schemas/jsonschema/` (request/response/document shapes)

If this document and the schemas disagree, the **schemas win**.

---

## Base URLs

### Local (DevBox)
- Gateway: `http://localhost:8080`
- Health: `GET /actuator/health`

---

## Request Correlation

dsearch uses a **correlation identifier** to connect logs across services.

### Header (HTTP)
- Request header: `X-Correlation-Id` (recommended)

If the header is absent, the gateway SHOULD generate a correlation id.

### Propagation
The same correlation id SHOULD be propagated across:
- Gateway → Query Nodes → Index Nodes
- Retries SHOULD reuse the same correlation id when possible

### Logging
All request-scoped logs SHOULD include `correlationId` (see `../../observability/logging.md`).

---

## Pagination

dsearch uses **cursorless pagination** (offset + size).

Request fields:
- `from` (zero-based offset, default `0`)
- `size` (page size, default `10`, max `100`)

Response fields:
- `page.from`
- `page.size`
- `page.totalElements`

Optional convenience fields:
- `page.totalPages`
- `page.first`
- `page.last`

Notes:
- `totalElements` is the count of all matches, not just the current page.
- Pagination is stable only if the underlying ranking is stable (see tie-breakers below).

---

## Ranking, Scores, and Tie-breakers

### Score semantics
Scores are **relative** ranking signals and MUST NOT be assumed comparable across:
- different modes (LEXICAL vs SEMANTIC vs HYBRID)
- different indices/shards
- different query nodes

### Tie-breakers
When two hits have equal score, ordering SHOULD be deterministic.

Recommended tie-breakers (in order):
1. Higher score
2. `docId` ascending (stable)
3. Shard id ascending (if applicable)

If your implementation uses a different deterministic tie-breaker, document it here.

---

## Search Modes

`mode` controls how dsearch executes queries:

- `LEXICAL` — Lucene term-based scoring (e.g., BM25)
- `SEMANTIC` — vector similarity scoring
- `HYBRID` — combines lexical + semantic signals

### Hybrid weights
For `HYBRID`, optional `weights` may be provided:
- `weights.lexical` (0..1)
- `weights.semantic` (0..1)

If omitted, the gateway applies default weights.

Weights SHOULD be treated as hints. Invalid weights (e.g., negative or >1) SHOULD cause a `400`.

---

## Shard Routing

Some APIs accept an optional `shardId`.

- If provided, the gateway SHOULD route the request to the shard directly.
- If omitted, the gateway routes using its configured sharding strategy.

Clients SHOULD NOT assume shard IDs are stable across deployments unless explicitly configured.

---

## Filters

`filters` is an optional key/value map.

Conventions:
- Filters SHOULD be low-cardinality (suitable for indexing/caching).
- Avoid high-cardinality filters (doc ids, user ids, raw query strings).

Filter keys SHOULD be stable and documented if used across clients.

---

## Document Shape

Indexed documents conform to `../schemas/jsonschema/search_document.schema.json`.

Key conventions:
- `docId` is required and stable.
- `title` and `content` are required and indexed for search.
- `metadata` is optional and SHOULD be low-cardinality.

Avoid storing secrets or sensitive data in documents intended for logs and demos.

---

## Error Model

dsearch returns JSON errors for HTTP endpoints.

Canonical shape:
- `error` (short category)
- `message` (human-readable)
- `correlationId` (optional)

Examples:
- `BAD_REQUEST`
- `NOT_FOUND`
- `INTERNAL`

Agents SHOULD use the `correlationId` to retrieve relevant logs.

---

## Idempotency

- Indexing a document with the same `docId` SHOULD be idempotent (overwrite semantics) unless otherwise documented.
- Deleting a document SHOULD be idempotent (deleting a missing doc still returns success).

---

## Timeouts and Retries

Clients SHOULD:
- use conservative timeouts for local calls
- retry only on transient failures (timeouts, connection resets)

Retries SHOULD:
- preserve the same correlation id
- avoid unbounded retry loops

---

## Compatibility

Breaking changes include:
- removing fields
- changing field types
- changing endpoint paths
- changing required behavior (e.g., semantics of pagination)

Non-breaking changes include:
- adding optional fields
- adding new endpoints

For formal rules, see `versioning.md` (if present).

---