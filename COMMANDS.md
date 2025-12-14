# Distributed Search Engine — Commands

This document describes the common development and operational commands
for the **dsearch** distributed search engine.

All commands are executed from the **repository root**.

---

## Prerequisites

- Java 21+
- Maven
- Bash (macOS / Linux)
- Ports required (default):
  - Coordinator: 50050 / 8081
  - Gateway: 50051 / 8080
  - Query Nodes: 50052+ / 8082+
  - Index Nodes: 6000+ / 8083+

---

## Build & Clean

### Build all modules (skip tests)

```bash
make build
```

Runs:
```bash
mvn clean package -DskipTests
```

Use this for fast local iteration.

---

### Clean Maven artifacts

```bash
make clean
```

Runs:
```bash
mvn clean
```

Removes all `target/` directories.

---

## Running the Cluster

### Run single-node cluster

```bash
make run
```

Starts:
- 1 Coordinator
- 1 Gateway
- 1 Query Node
- 1 Index Node

This is the recommended mode for local development.

---

### Run multi-node cluster

```bash
make run-multi
```

Starts multiple Query and Index nodes using environment variables:

```bash
N_INDEX_NODES=2
N_QUERY_NODES=2
make run-multi
```

Defaults:
- `N_INDEX_NODES=2`
- `N_QUERY_NODES=2`

---

### Stop all cluster processes

```bash
make stop
```

- Kills all running cluster processes
- Clears all log files

Use this before restarting the cluster.

---

### Restart cluster (convenience)

```bash
make restart
```

Equivalent to:
```bash
make stop
make run
```

---

## Logs & Debugging

### Tail all logs

```bash
make logs
```

Continuously tails all files under `logs/`.

Press `Ctrl + C` to exit.

---

## Data & State Management

### Wipe indexed data

```bash
make wipe-data
```

Deletes all files under `data/`.

Use this when you want a clean index state.

---

### Full reset (clean + stop + wipe)

```bash
make reset
```

Performs:
- `mvn clean`
- stops all running nodes
- deletes logs
- deletes indexed data

Use this when the cluster gets into a bad state.

---

## Typical Development Workflow

```bash
make build
make run
# make changes
make stop
make run
```

For multi-node testing:

```bash
N_INDEX_NODES=3 N_QUERY_NODES=2 make run-multi
```

---

## Notes

- All runtime logs are written to `logs/`
- All index / state data is written to `data/`
- Cluster lifecycle scripts live under `scripts/`
- This project assumes local execution (no Docker)

---

## Troubleshooting

- **Ports already in use** → run `make stop`
- **Weird state / stale data** → run `make reset`
- **Build failures** → run `make clean && make build`

---

## For AI / Agent Usage

When interacting with this repository:
- Prefer `make build` over raw Maven commands
- Use `make run` for local testing
- Use `make stop` before modifying cluster scripts
- Use `make reset` when cluster state is unclear