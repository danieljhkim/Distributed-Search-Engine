
# Quickstart

This quickstart guide will help you set up a multi-node cluster of the Vector Search Engine on your local machine.

--- 

### Prerequisites
- Java 21+

---

### Start a local multi-node cluster

Package the project:
```bash
make build
```

Spin up a full multi-node cluster:

```bash
make run-multi
```

This starts:
- 2 Index Nodes (ports 5000, 5001)
- 2 Query Nodes (ports 6000, 6001)
- Gateway (HTTP API at http://localhost:8080)

### Coordinator state in Docker Compose

`docker-compose.yml` mounts the named `coordinator-state` volume at `/data` in
the coordinator container. The authoritative state file is
`/data/coordinator-topology.properties`; its same-version backup is
`/data/coordinator-topology.properties.bak`. Recreating the coordinator
container retains both files because the named volume is not removed by
`docker compose rm` or `docker compose up --force-recreate`.

The coordinator writes a fully synchronized temporary file and atomically
replaces the authoritative state file. It preserves the topology epoch and
monotonic topology version across ordinary restarts. It never starts with a
fresh epoch when an existing state file is corrupt, truncated, or from an
unsupported format version: startup fails and requires deliberate recovery.

Coordinator state alone is not a recoverable search backup: it must be from
the same consistency boundary as every Lucene shard and the schema/model
metadata used to build and query those shards. Use the supported manifest,
snapshot, empty-deployment restore, and public-query drill in
[RECOVERY.md](RECOVERY.md). Do not copy a live volume or replace coordinator
files independently to recover a cluster.

The coordinator persistence tests exercise the same backup-and-restore flow
and verify that corrupt and incompatible state fails explicitly rather than
silently resetting topology.

The default test suite covers the persistence contract. To exercise a real
container replacement against a fresh named volume, run:

```bash
mvn test -pl dk.coordinator -am \
  -Dtest=CoordinatorContainerRestartIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Ddsearch.docker.it=true
```

### Index sample data

```bash
cd benchmark
make ingest
```

### Run a search

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H "Content-Type: application/json" \
  -d '{
    "query": "time travel romance",
    "page": 0,
    "pageSize": 10,
    "shardId": "0",
    "searchType": "HYBRID"
  }'
```

This returns BM25 + Semantic fused results.

### Stop the cluster

```bash
make stop
```
