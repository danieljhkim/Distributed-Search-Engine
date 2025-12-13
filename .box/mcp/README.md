# DevBox MCP (MVP)

This directory defines the **Model Context Protocol (MCP) contract** for DevBox.

It is intentionally **minimal**.

The goal of the MVP is to make a DevBox-enabled repository **operable by AI agents** without expanding the execution surface or coupling DevBox to any specific runtime.

---

## What This Is

- A **tool contract** (`server.json`) that describes what agents are allowed to do
- A **stable interface** between DevBox and any MCP-compatible host (e.g. Cursor)
- A **policy-respecting control plane** over DevBox commands

This folder defines **what is exposed**, not **how it is implemented**.

---

## What This Is Not

- ❌ Not the DevBox specification (see `SPEC.md`)
- ❌ Not a required component to use DevBox
- ❌ Not a production MCP server
- ❌ Not an agent sandbox

This is an **adapter contract**, nothing more.

---

## MVP Tool Surface

The MVP exposes a small, deliberate set of tools.

### `box.run`
Run a named, allowlisted DevBox command.

- Commands must exist in `.box/box.yaml`
- Commands must be permitted by `.box/policies.yaml`
- Arbitrary shell execution is not allowed

---

### `box.health`
Run the DevBox health check.

- Delegates to the `health` command defined in `.box/box.yaml`
- Intended for readiness checks and iteration loops

---

### `box.read_logs`
Read runtime logs from `logs/`.

- Supports globbing and tailing
- Read-only
- Bounded by policy

---

### `box.read_contract`
Read DevBox contract files.

Examples:
- `.box/box.yaml`
- `.box/policies.yaml`
- `.box/contracts/*`

---

## Design Principles (MVP)

- **Minimal surface area**
- **No implicit behavior**
- **Policy-first**
- **Protocol-agnostic**
- **Spec before implementation**

If a capability is not explicitly defined here, it is not supported.

---

## Relationship to DevBox

```text
DevBox (spec + scripts)
        ↓
   MCP contract
        ↓
 MCP host / agent
```

DevBox does not depend on MCP.  
MCP adapters depend on DevBox.

---

## Extensibility

Future versions MAY add:
- MCP resources (state, health snapshots)
- Structured outputs (JSON)
- Audit metadata

All extensions must preserve backward compatibility with the MVP tool surface.

---

## Status

This is an **MVP contract**.

It is expected to evolve as:
- agent capabilities mature
- DevBox usage patterns stabilize
- safety requirements become clearer

Stability is prioritized over completeness.