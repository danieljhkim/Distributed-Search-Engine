
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

Back up the state only while the coordinator is stopped. The default Compose
project name in this repository is `dsearch`, so the named volume is
`dsearch_coordinator-state`:

```bash
mkdir -p backups/coordinator
docker compose stop coordinator
docker run --rm \
  -v dsearch_coordinator-state:/state:ro \
  -v "$PWD/backups/coordinator":/backup \
  alpine:3.20 sh -ceu '
    cp /state/coordinator-topology.properties /backup/coordinator-topology.properties
    cp /state/coordinator-topology.properties.bak /backup/coordinator-topology.properties.bak
  '
docker compose start coordinator
```

To restore a known-good pair, stop the coordinator, atomically replace both
files in the volume, then start it. Do not delete the volume or remove the
state files to work around a failed startup: that would create a new epoch and
discard authoritative topology.

```bash
docker compose stop coordinator
docker run --rm \
  -v dsearch_coordinator-state:/state \
  -v "$PWD/backups/coordinator":/backup:ro \
  alpine:3.20 sh -ceu '
    test -s /backup/coordinator-topology.properties
    test -s /backup/coordinator-topology.properties.bak
    cp /backup/coordinator-topology.properties /state/coordinator-topology.properties.restore
    mv -f /state/coordinator-topology.properties.restore /state/coordinator-topology.properties
    cp /backup/coordinator-topology.properties.bak /state/coordinator-topology.properties.bak.restore
    mv -f /state/coordinator-topology.properties.bak.restore /state/coordinator-topology.properties.bak
  '
docker compose start coordinator
```

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
