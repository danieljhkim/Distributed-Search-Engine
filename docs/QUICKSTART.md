
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