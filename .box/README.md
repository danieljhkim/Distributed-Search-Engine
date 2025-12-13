# DevBox (.box)

This directory defines the **DevBox execution contract** for this repository.

Everything inside `.box/` exists to make the system:
- deterministic
- observable
- safe to operate (by humans *and* agents)

If it is not defined here, it is **not part of the DevBox contract**.

---

## What Lives Here

```text
.box/
├── box.yaml           # Source of truth: commands, env, signals
├── policies.yaml      # Execution guardrails (deny by default)
├── scripts/           # Deterministic command implementations
├── state/             # Runtime state (pids, ports, metadata)
├── contracts/         # APIs, schemas, invariants (optional)
└── mcp/               # MCP adapter contract & server (optional)
```

---

## box.yaml

`box.yaml` is the **authoritative contract**.

It defines:
- which commands exist (`up`, `down`, `health`, `test`, etc.)
- the intent of each command
- which environment variables tune execution
- where signals (logs, reports, state) are produced

Scripts may evolve.  
`box.yaml` should change **rarely**.

---

## scripts/

Each script implements exactly **one** DevBox command.

Rules:
- one command → one script
- deterministic behavior
- non-zero exit on failure
- no implicit side effects

Scripts MUST:
- write logs to `logs/`
- write state to `.box/state/`
- write artifacts to `.box/reports/` (if applicable)

---

## policies.yaml

`policies.yaml` defines **what is allowed**.

This file exists to make agent operation safe.

Policies:
- are deny-by-default
- explicitly allow commands
- restrict readable paths
- restrict writable paths

If you do not understand this file, **do not loosen it**.

---

## state/

`.box/state/` contains **ephemeral runtime metadata**:
- process IDs
- ports
- timestamps
- coordination info

Rules:
- no secrets
- safe to delete
- may be regenerated at any time

---

## contracts/ (optional)

This directory may contain:
- OpenAPI specifications
- protobuf files
- schemas
- invariants

These files are **read-only signals** for humans and agents.

---

## mcp/ (optional)

If present, this directory defines the **MCP adapter contract**.

It exposes a *restricted* control surface:
- run allowlisted commands
- read logs
- read contracts
- check health

DevBox does **not** depend on MCP.  
MCP adapters depend on DevBox.

See `.box/mcp/README.md` for details.

---

## Mental Model

- `.box/` defines **what exists**
- scripts define **how it runs**
- policies define **what is allowed**
- signals define **what machines observe**

This boundary is intentional.

Cross it carefully.