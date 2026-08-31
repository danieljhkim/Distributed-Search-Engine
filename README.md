# dsearch (Distributed Search Engine)

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-007396?style=for-the-badge&logo=openjdk&logoColor=white">
  <img src="https://img.shields.io/badge/Apache_Lucene-9.x-orange?style=for-the-badge&logo=apache&logoColor=white">
  <img src="https://img.shields.io/badge/gRPC-1.x-34A7C1?style=for-the-badge&logo=grpc&logoColor=white">
  <img src="https://img.shields.io/badge/Spring_Boot-3.x-6DB33F?style=for-the-badge&logo=springboot&logoColor=white">
  <img src="https://img.shields.io/badge/Vector_Search-Embeddings-4B0082?style=for-the-badge&logo=target&logoColor=white">
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/License-MIT-green?style=for-the-badge">
  </a>

</p>

`dsearch` is a horizontally scalable, Lucene‑based distributed search engine written in Java 21.

`main` is the authoritative integration and release branch. Development commits use the root Maven
`revision` with a `-SNAPSHOT` suffix; a release sets that one value to its final version, tags the
`main` commit as `v<version>`, and publishes images tagged with the same version.

It targets **small to medium‑sized applications** that need:
- **Lexical search (BM25)**
- **Semantic search (vector kNN)**
- **Hybrid ranking (BM25 + embeddings)**

…without the operational and conceptual overhead of a full Elasticsearch / OpenSearch cluster.

---

## Overview

The system is composed of three primary components:

- **Gateway Node**  
  Spring Boot HTTP API entrypoint, load balancing and routing, and system health.

- **Query Nodes**  
  gRPC services that **fan‑out queries to all index nodes for a given partition**, merge partial results, and apply hybrid fusion strategies.

- **Index Nodes**  
  gRPC services hosting **Lucene shards**. Each index node can host multiple partitions (e.g. `shard-movies`, `shard-shows`, …), and sharded across multiple nodes. 
  Each partition is a Lucene index responsible for a categorical or domain‑specific slice of your data.

- **Coordinator Node**  
  Optional, durable membership and topology authority. It atomically persists the topology epoch,
  version, membership, health, and leases to `serviceDiscovery.coordinatorStateFile` (plus a
  backup), exposes health-aware discovery, and removes expired non-coordinator leases. It does not
  replicate data or move existing Lucene documents.

### Sharding & Load Balancing

- Shards represent **logical partitions** of your data (e.g., by domain or category).
- Each shard exists **once per cluster** (no replicas yet).
- Shards are distributed across index nodes.
- Every document has **exactly one owning index node**, derived from the document key:
  - For a given `(partitionId, documentId)` write/delete, the Gateway routes to
    `owner = argmax hash(nodeId, partitionId, documentId)` over the configured index nodes
    (rendezvous hashing). A Lucene upsert is local to one node, so sending an update anywhere
    else would leave the cluster holding two copies of the same document.
  - The mapping is a pure function of the key and `app-config.yaml`, so it is identical in
    every Gateway process and across restarts, and it does not move when nodes go unhealthy.
  - If the owner is unavailable the mutation fails with `503` instead of being rerouted.
  - Hashing also spreads documents evenly across nodes without a coordinator.
  - The Gateway still keeps **per‑shard, per‑node document counts**, updated only after a
    node confirms a mutation and snapshotted to disk, but they are now an observability
    signal rather than a routing input.
  - See [Document Ownership](./docs/DOCUMENT_OWNERSHIP.md) for restart and topology‑change behavior.

There is **no replication layer** yet. If an index node goes down, documents stored on that node’s shards are temporarily unavailable until the node comes back up and reloads its Lucene indices.

The coordinator exposes a versioned, health-aware node registry. `RegisterNode` creates or updates
membership, `Heartbeat` renews a previously registered node's lease without creating membership,
and `GetShardMap` returns one logical `index/<nodeId>` placement for each active index node. Those
RPCs return the durable topology epoch and monotonic version so clients can reject stale state;
they do not provide replication, automatic rebalancing, or document movement.

This design intentionally keeps the system:
- **Simple to operate** (few moving parts)
- **Easy to reason about**
- **Horizontally scalable** by just adding more index/query nodes and updating config.

---

## Architecture Overview

```text
          +--------------+
          |   Client     |
          +------+-------+
                 |
           HTTP /search
                 |
        +--------v---------+
        |    Gateway Node  |           
        | (Spring Boot API)|             
        +--------+---------+           
                 |                            +--------v----------+
                 |                            | Coordinator Node  |
                 |----------------------------| - registry        |
                 |                            | - node membership |
                 | gRPC QueryService          | - health checks   |
                 |                            +--------+----------+
        +--------v---------+             
        |    Query Nodes   |             
        |  - Fan-out RPCs  |          
        |  - Merge Results |
        +--------+---------+
                 |
                 | gRPC IndexService
                 |
   +-----------------------------+
   |        Index Nodes          |
   |-----------------------------|
   | shard-0 | shard-1 | shard-2 |
   |  Lucene |  Lucene |  Lucene |
   +-----------------------------+
```

---

## Search Flow (BM25 + Semantic Search with Lucene kNN)

The engine supports **two complementary retrieval modes** that can be used independently or combined:

1. **Lexical Search (BM25)** – classic keyword relevance
2. **Semantic Search (Embeddings + Lucene kNN)** – retrieves results by *meaning*, not by exact words
3. **Hybrid Search** – fuses BM25 and semantic scores into a single ranked list

### 1. Document Indexing

For each document:

- All textual fields are concatenated into a single representation.
- A dense embedding is generated using a default transformer model:
  - `all-MiniLM-L6-v2` via DJL (`textEmbedding` model in `app-config.yaml`).
  - or any other compatible model you configure.
- The document is stored in Lucene as:
  - A **BM25 text field** for lexical search.
  - A **vector field** (`KnnVectorField`) for semantic similarity.

This enables BM25, semantic, and hybrid retrieval over the **same underlying data**.

### 2. Query Execution Modes

#### A. BM25 Search (Keyword)

- Lucene processes the query using BM25.
- Best for **exact keywords**, short queries, and when you care about precise term matches.
- Typically low‑latency.

#### B. Semantic Search (Embedding kNN)

- Query text is embedded using the same transformer model as indexing.
- Query Node fans out to all Index Nodes that host the requested shard/category.
- Each Index Node runs Lucene HNSW‑based kNN over its vector field.
- Results are merged and sorted by semantic similarity.
- Great for **natural‑language queries** and conceptual similarity (e.g., “space opera about time dilation”).

#### C. Hybrid Search (BM25 + Semantic Fusion)

To combine lexical and semantic signals:

1. Run BM25 search → top K
2. Run semantic kNN search → top K
3. Merge hits by document ID
4. Apply a fusion strategy:
   - **RRF**: Reciprocal Rank Fusion - rank‑based blending (default)
   - **score_sum:** bm25Score + semanticScore
   - **weighted:** α·bm25 + β·semantic
5. Paginate the fused list and return to the client.

---

## Quick Start

For full details, see [Quick Start Guide](./docs/QUICKSTART.md).

### Prerequisites

- Java 21
- Maven 3.9+
- (Optional) `k6` / `ghz` for load/latency testing

### Build & Run (multi‑node demo cluster)

```bash
# from repo root
make build

# Start a local cluster with 2 index nodes, 2 query nodes, 1 coordinator, and 1 gateway
make run-multi

# Cluster layout (by default):
#  - Index Nodes : 5000, 5001
#  - Query Nodes : 6000, 6001
#  - Coordinator  : 7000
#  - Gateway     : http://localhost:8080
```

### Example HTTP API Requests

#### Search Request

```json
{
  "query": "time travel romance",
  "page": 0,
  "pageSize": 10,
  "partitionId": "movies",
  "searchType": "HYBRID",  // BM25 | SEMANTIC | HYBRID
  "fusionStrategy": "RRF" // SCORE_SUM | WEIGHTED | RRF
  "filters": [
    {
      "field": "year",
      "operator": "GTE",
      "values": ["2000"]
    }
  ],
  "facets": [
    {
      "field": "genre",
      "size": 10
    },
    {
      "field": "year",
      "size": 5
    }
  ]
}
```

#### Index Request

```json
{
  "partitionId": "movies",
  "id": "movie_001",
  "fields": {
    "title": "Interstellar",
    "content": "A team of explorers travel through a wormhole...",
    "year": "1999",
    "genre": "sci-fi",
    "rating": "8.7",
    "createdAt": "915148800000"
  }
}
```

---

## Observability

`dsearch` is instrumented end‑to‑end so you can see what your cluster is doing under load.

### Gateway (HTTP, Spring Boot + Micrometer)

The Gateway uses **Spring Boot Actuator + Micrometer + Prometheus registry**:

- **Metrics endpoints**
  - `GET http://localhost:8080/actuator/metrics` – metric catalog
  - `GET http://localhost:8080/actuator/metrics/dsearch.search.http` – HTTP search handler metric
  - `GET http://localhost:8080/actuator/metrics/dsearch.index.http` – HTTP index handler metric
  - `GET http://localhost:8080/actuator/prometheus` – Prometheus scrape endpoint

- **Key metrics** (examples):
  - `dsearch.search.http` – high‑level timing for the `/api/v1/search` handler.
  - `dsearch.gateway.search.latency{searchType,shardId}` – fine‑grained latency per search type and shard.

### gRPC Nodes (Query / Index)

Both Query Nodes and Index Nodes expose **Prometheus‑compatible `/metrics` endpoints** (via Prometheus Java client):

- JVM metrics (GC, memory, threads) via `DefaultExports.initialize()`
- gRPC server metrics via `PrometheusGrpcServerInterceptor`:
  - `dsearch_grpc_server_latency_seconds{service,method,status}`
  - `dsearch_grpc_server_requests_total{service,method,status}`

On the Gateway side, gRPC clients are instrumented with a **Prometheus gRPC client interceptor**:

- `dsearch_grpc_client_latency_seconds{component,service,method,status}`
- `dsearch_grpc_client_requests_total{component,service,method,status}`

This lets you compare **client‑side** vs **server‑side** latency per RPC method and component (e.g. `gateway->query-node`).

### Health Endpoints

- **Gateway** – aggregated health across itself and downstream nodes
  - `GET /health` – Gateway health
  - `GET /cluster/health` – Overall Cluster health

- **Query Nodes / Index Nodes** – each exposes a lightweight HTTP health check endpoint
  - `GET /health` – Node health

### Resilience gate

Behaviour under overload, exhausted admission capacity, slow or lost downstreams, index disk-full
and read-only storage, coordinator restart, and rolling node replacement is exercised by
`make resilience`. See [Operability](./docs/OPERABILITY.md) for the scenarios, the evidence it
records, and its CI profile.

---

## Benchmarks

Detailed methodology and raw results are documented in [Benchmarks](./docs/BENCHMARKS.md).

See the benchmarks document for:
- `k6` HTTP load‑test scripts for Gateway
- `ghz` gRPC benchmarks for Query Node / Index Node
- How to reproduce and extend these benchmarks on your own hardware

---

## Configuration

Cluster configuration is defined in `app-config.yaml` and loaded into the Gateway and nodes at startup:

```yaml
serviceDiscovery:
  enabled: true
  refreshIntervalSeconds: 30

indexNodes:
  routingStrategy: "LEAST_LOADED"
  componentLabel: "dsearch-index-node"
  # Current runtime has no replication layer; this is documented for future work.
  replicationFactor: 1
  nodes:
    - id: "0"
      host: "localhost"
      port: 5000
      healthPort: 5100

queryNodes:
  routingStrategy: "ROUND_ROBIN"
  componentLabel: "dsearch-query-node"
  nodes:
    - id: "0"
      host: "localhost"
      port: 6000
      healthPort: 6100

coordinatorNodes:
  routingStrategy: "ROUND_ROBIN"
  componentLabel: "dsearch-coordinator-node"
  nodes:
    - id: "0"
      host: "localhost"
      port: 7000
      healthPort: 7100

ml:
  models:
    textEmbedding:
      url: "djl://ai.djl.huggingface.pytorch/sentence-transformers/all-MiniLM-L6-v2"
      engine: "PyTorch"
```

- `indexNodes.routingStrategy` currently supports **`LEAST_LOADED`**, using per‑shard, per‑node doc counts.
- `queryNodes.routingStrategy` currently supports **`ROUND_ROBIN`** for fan‑out queries across multiple query node instances.
- With discovery enabled, index and query clients require a versioned coordinator response before
  routing. During a coordinator outage they may use only a previously accepted topology for
  `maxStalenessSeconds`; they do not silently return to the configured static node list.
- The coordinator persists its membership/topology state at `coordinatorStateFile` (or the
  `COORDINATOR_STATE_FILE` override), supports registration, lease heartbeats, health checks,
  expiry, discovery, and the logical shard-map RPC. The shard map is metadata only: it does not
  rebalance Lucene data or add replication.

---

## Limitations & Roadmap

This project is intentionally minimal and educational. Some trade‑offs and potential future work:

- **No replication layer (yet)**
  - A shard lives on exactly one node; if that node goes down, its data is unavailable until restart.
  - Future direction: coordinator‑driven replication / Raft‑based shard groups.
- **Coordinator membership is not a data rebalancer**
  - Heartbeat and shard-map RPCs are implemented for durable, versioned membership and logical
    index-node placement; they do not move or replicate Lucene documents.
  - Expired node leases are removed from coordinator discovery. Node removal, shard relocation,
    and data rebalancing remain manual operational work.
- **No rebalancing when the index-node list changes**
  - Adding or removing an entry under `indexNodes` reassigns part of the document ownership
    ring, and nothing moves the affected documents, so the change requires a reindex.
  - Future direction: coordinator-driven handoff of the reassigned key range.

---

## License

This repository is intended as an educational and portfolio project.
This project is licensed under the MIT License. 

See the [LICENSE](LICENSE) file for details.
