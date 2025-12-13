# API Versioning (dsearch)

This document defines how dsearch evolves its public contracts over time.

Contracts include:
- HTTP Gateway API (`http.md`, `../schemas/openapi.yaml`)
- gRPC APIs (`grpc.md`, `../schemas/proto/`)
- JSON Schemas (`../schemas/jsonschema/`)
- Configuration schema (`../schemas/config/`)

The goal is to make change **explicit**, **reviewable**, and **safe** for humans and agents.

---

## Contract Identifiers

dsearch versioning distinguishes between:

- **Implementation version** — git tags/releases (e.g., `v0.1.0`)
- **Contract version** — schemas and documents under `.box/contracts/`

Implementation versions may change without changing the contract.
Contracts must not change silently.

---

## Backwards Compatibility Rules

### Non-breaking (additive)
These changes are backwards compatible:

- adding a new endpoint or RPC method
- adding optional fields
- adding new enum values (only if clients handle unknown values safely)
- adding new response fields
- tightening docs without changing schema

### Breaking
These changes are considered breaking:

- removing endpoints / RPC methods
- changing endpoint paths
- removing fields
- changing field types
- changing required fields
- changing semantics of existing fields
- changing pagination behavior
- changing score semantics or ordering rules
- renaming services or protobuf packages
- reusing protobuf field numbers for a different meaning

---

## Versioning Strategy

### HTTP
- Use path-based versioning: `/api/v1/...`
- Breaking changes require a new major path version:
  - `/api/v2/...`
- Old versions may be deprecated but should remain available for a deprecation window (local/dev only).

### gRPC
- Prefer additive evolution: add fields, add methods.
- For breaking changes, version the protobuf package or service name:
  - `dsearch.query.v1` → `dsearch.query.v2`

Do not delete or reuse protobuf field numbers.

### Schemas
- Keep schemas stable and additive.
- For breaking schema changes, create a new schema id or file:
  - `search_request.v2.schema.json` (or update `$id` with a new version)
- Keep old schema versions for reference during migration.

### Configuration
- Prefer additive changes.
- For breaking config changes:
  - introduce new keys alongside old keys
  - provide a migration note
  - allow both during a transition window when feasible

---

## Deprecation

When deprecating something:

- mark it as deprecated in docs and schema comments
- keep it functional for a defined window
- remove only in the next breaking version

Deprecation should be explicit in:
- OpenAPI descriptions
- `.proto` comments
- contract docs

---

## Change Process

All contract changes SHOULD:

1. Update the canonical schema (`openapi.yaml`, `.proto`, JSON Schema)
2. Update the human docs (`http.md`, `grpc.md`, `conventions.md`)
3. Update CHANGELOG / release notes
4. Preserve examples (or update them)

If a change is breaking, it MUST:
- bump the API version (`/v2`, protobuf package `v2`, etc.)
- document migration notes

---

## Summary

- Implementation versions track code.
- Contract versions track promises.
- Prefer additive change.
- Make breaking changes obvious by versioning.
