

# DevBox Environment Files

DevBox is intentionally language-agnostic. To keep the contract portable, DevBox uses a small set of
**generic environment variables** that control how the runtime is started/stopped and how health is checked.

This folder contains **examples** you can copy and customize per repository.

---

## Files

### `.env.example`
A reference list of variables a DevBox-enabled repo may use.

### `.env.local.example`
A template for **local, developer-specific overrides**.

Recommended workflow:
1. Copy:
   ```bash
   cp .box/env/.env.local.example .box/env/.env.local
   ```
2. Edit `.box/env/.env.local` and fill in values.

> `.box/env/.env.local` should be **gitignored** (local-only).

---

## DevBox Variables (MVP)

These variables are used by the DevBox scripts (e.g. `.box/scripts/up.sh`, `.box/scripts/down.sh`, `.box/scripts/smoke.sh`).

### `BOX_UP_CMD`
Command string DevBox runs to start the local runtime.

Examples:
- `./scripts/run_cluster_multi.sh`
- `make up`
- `mvn -q -DskipTests spring-boot:run`

### `BOX_DOWN_CMD`
Command string DevBox runs to stop the local runtime.

Examples:
- `./scripts/run_cluster_multi.sh stop`
- `make down`

### `BOX_HEALTH_URL`
HTTP endpoint used by health/smoke checks.

Examples:
- `http://localhost:8080/actuator/health`
- `http://localhost:8080/health`

### Health tuning
Used by `.box/scripts/smoke.sh --health` to handle slow-starting runtimes.

- `BOX_HEALTH_TIMEOUT_SECS` (default: `2`)
- `BOX_HEALTH_RETRIES` (default: `20`)
- `BOX_HEALTH_SLEEP_SECS` (default: `1`)

---

## How env gets into scripts

DevBox scripts read variables from the process environment.
There are two common approaches:

### Option A: export in your shell
```bash
export BOX_UP_CMD="./scripts/run_cluster_multi.sh"
export BOX_DOWN_CMD="./scripts/run_cluster_multi.sh stop"
export BOX_HEALTH_URL="http://localhost:8080/actuator/health"
```

### Option B: load from a file (recommended)
If you want scripts to automatically load `.box/env/.env.local`, you can source it at the top of your `up.sh` / `down.sh`
(or in a shared loader script). Keep the loader **repo-local** and **explicit** to avoid surprising behavior.

---

## Notes for AI agents

When an agent uses MCP tools like `box.run("up")`, the runtime commands ultimately depend on `BOX_UP_CMD` / `BOX_DOWN_CMD`.
For safety, keep these commands:
- deterministic
- allowlisted in `.box/policies.yaml`
- free of destructive side effects