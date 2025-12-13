# DevBox Contract — Required Agent Behavior

This repository uses **DevBox** as the *only supported execution surface*.

AI agents MUST operate strictly within the DevBox contract.  
Do not bypass it, even if other tools appear available.

---

## Allowed Actions

Agents may only execute actions through DevBox:

### CLI
- `devbox doctor`
- `devbox up`
- `devbox health`
- `devbox test`
- `devbox logs`
- `devbox down`

### MCP Tools
- `box-run`
- `box-health`
- `box-read-logs`
- `box-read-contract`

If an action is required that is not listed above, **modify the DevBox configuration** (`.box/box.yaml` and scripts) instead of executing ad-hoc commands.

---

## Forbidden Actions

Agents MUST NOT:

- Run arbitrary shell commands
- Invoke `make`, `mvn`, `npm`, `gradle`, or similar tools directly
- Start or stop services outside of DevBox
- Read or write files outside the paths declared in the DevBox contract
- Assume environment state not declared by DevBox

---

## Validation & Observation

- Use `devbox health` to determine readiness
- Use `devbox test` for validation
- Use `devbox logs` or MCP log tools for observation
- Do not infer success from command output alone

---

## Design Intent

DevBox exists to make local systems **deterministic, observable, and safe** for non-human operators.

If you encounter friction:
- Do not bypass the contract
- Extend it explicitly

Failure to follow these rules is considered incorrect behavior.
