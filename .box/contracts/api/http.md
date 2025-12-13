# HTTP API (dsearch Gateway)

This document describes the **HTTP API contract** exposed by the dsearch Gateway.
It is a human-readable guide that complements the canonical OpenAPI specification:

- `../schemas/openapi.yaml`

If this document and the OpenAPI spec disagree, the **OpenAPI spec wins**.

---

## Overview

The Gateway is the primary HTTP entrypoint into dsearch.

Responsibilities:
- accept client requests
- perform request validation
- route queries to query/index nodes
- merge and return results
- generate and propagate correlation IDs

The Gateway is designed for **local-first development** under DevBox.

---

## Base URL

### Local (DevBox)
```
http://localhost:8080
```

Health endpoint:
```
GET /actuator/health
```

---

## Headers

### Correlation ID
Clients SHOULD send:
```
X-Correlation-Id: <string>
```

If absent, the gateway will generate one.

The correlation id is:
- echoed in logs
- propagated to downstream services
- included in error responses when available

---

## Endpoints

### Health

#### GET /actuator/health

Readiness and liveness signal used by DevBox and agents.

Example:
```bash
curl http://localhost:8080/actuator/health
```

---

### Search

#### POST /api/v1/search

Execute a search query.

Supports:
- LEXICAL
- SEMANTIC
- HYBRID

Example:
```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-Id: demo-1' \
  -d '{
    "query": "vector search engine",
    "mode": "HYBRID",
    "from": 0,
    "size": 10,
    "weights": {
      "lexical": 0.5,
      "semantic": 0.5
    }
  }'
```

Notes:
- `from` is zero-based
- `size` defaults to 10, max 100
- scores are relative (see `conventions.md`)

---

### Index Document

#### POST /api/v1/documents

Index or overwrite a document.

Example:
```bash
curl -X POST http://localhost:8080/api/v1/documents \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-Id: demo-2' \
  -d '{
    "document": {
      "docId": "doc-1",
      "title": "Storage System",
      "content": "Distributed storage and search system",
      "metadata": {
        "source": "demo"
      }
    }
  }'
```

Behavior:
- indexing the same `docId` overwrites the document
- operation is idempotent

---

### Delete Document

#### DELETE /api/v1/documents/{docId}

Delete a document by id.

Optional query parameter:
- `shardId` — routing hint

Example:
```bash
curl -X DELETE "http://localhost:8080/api/v1/documents/doc-1"
```

Delete is idempotent; deleting a missing document returns success.

---

## Errors

Errors are returned as JSON.

Canonical shape:
```json
{
  "error": "BAD_REQUEST",
  "message": "Invalid search mode",
  "correlationId": "demo-1"
}
```

Common error categories:
- `BAD_REQUEST`
- `NOT_FOUND`
- `INTERNAL`

Clients SHOULD use the correlation id to locate relevant logs.

---

## Status Codes

Typical status codes:
- `200` — successful read/search
- `201` — document indexed
- `204` — document deleted
- `400` — invalid request
- `500` — internal error

---

## Timeouts and Retries

Clients SHOULD:
- use short timeouts for local development
- retry only on transient failures (timeouts, connection errors)

Retries SHOULD preserve the same correlation id.

---

## Compatibility

Breaking changes include:
- removing endpoints
- changing request/response shapes
- changing endpoint paths

Non-breaking changes include:
- adding optional fields
- adding new endpoints

See `versioning.md` (if present) for formal rules.

---
