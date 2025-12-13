# Security & Trust Boundaries (dsearch)

This document describes **security assumptions and trust boundaries** for dsearch, especially when operated under DevBox and by AI agents.

This is not a full threat model. The goal is to make local development **safe-by-default** and to prevent accidental exposure of sensitive capabilities.

---

## Scope

This document covers:
- Local DevBox runs (`devbox up`, `devbox test`, MCP tools)
- The Gateway HTTP API (local)
- Internal gRPC communication between services
- Logs, reports, and artifacts produced locally

Out of scope:
- Production deployment security
- Network perimeter security beyond local development
- Authentication/authorization for internet-exposed systems

---

## Trust Model (Local)

Local DevBox assumes:
- services bind to `localhost` only (preferred)
- the developer machine is the security boundary
- no untrusted external traffic is expected

DevBox MUST NOT assume:
- a clean environment
- exclusive port ownership
- absence of malicious local processes

---

## Default Local Exposure

### Gateway
- Intended: `http://localhost:8080`
- Health: `GET /actuator/health`

### gRPC Services
- Coordinator: `localhost:7000`
- Query Nodes: `localhost:6000..6001`
- Index Nodes: `localhost:5000..5001`

Recommendation:
- Bind to `127.0.0.1` explicitly where supported.
- Avoid binding to `0.0.0.0` for local runs.

---

## Authentication and Authorization

Local DevBox runs assume **no authentication** by default.

- No API keys
- No tokens
- No user identity

If you add auth later, document:
- required headers
- token format
- local secrets handling

For now, treat all endpoints as **trusted-local only**.

---

## Secrets Handling

### Do not store secrets in:
- `.box/box.yaml`
- `.box/contracts/`
- `logs/`
- `.box/reports/`
- example payloads checked into git

### Local env files
Use:
- `.box/env/.env.local` (gitignored)

Guidance:
- keep secrets scoped to local-only test credentials
- prefer short-lived tokens
- rotate frequently if used

---

## Agent Safety (DevBox Policies)

Agents MUST operate within the DevBox contract.

- Do not allow arbitrary shell execution through MCP.
- Only expose a minimal tool surface (`box-run`, `box-health`, `box-read-logs`, `box-read-contract`).
- Enforce allowlists/denylists for paths where possible.

If an agent needs a new capability:
- add it explicitly to DevBox scripts
- review the policy impact
- prefer read-only access to logs/contracts over write access

---

## Input Safety

Even locally, treat external inputs as untrusted.

### Gateway input
- validate request bodies (schema validation)
- cap payload sizes
- avoid logging full request bodies at INFO

### Search queries
- do not log raw queries at INFO level
- sanitize or truncate at DEBUG

### Document indexing
- avoid logging full document content
- truncate content in logs if needed

---

## Resource Safety

Local runs should include conservative limits:

- bounded logs (avoid unlimited growth)
- bounded retries
- conservative thread pools
- timeouts on network calls

DevBox `doctor` SHOULD detect common risks:
- port conflicts
- missing env vars
- missing log directories

---

## Observability as a Security Tool

Logs are a primary signal.
They should be useful without leaking sensitive data.

- prefer structured logs
- include `correlationId`
- avoid logging secrets

---

## Dependency and Supply Chain Notes

- lock Node dependencies for the MCP server (`package-lock.json` / `pnpm-lock.yaml`)
- pin Java toolchains where possible (e.g., Java 21)
- avoid executing downloaded scripts without review

---

## Summary

dsearch local security is built on these principles:

- **local-first** (bind to localhost)
- **no secrets in git**
- **minimal agent surface**
- **validate inputs**
- **logs without leakage**
- **explicit contracts over implicit behavior**
