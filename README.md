# Distributed Search Engine (dsearch)

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
  gRPC services that **fan‑out queries to all index nodes for a given shard**, merge partial results, and apply hybrid fusion strategies.

- **Index Nodes**  
  gRPC services hosting **Lucene shards**. Each index node can host multiple shards (e.g. `shard-movies`, `shard-shows`, …). Each shard is a Lucene index responsible for a categorical or domain‑specific slice of your data.

### Sharding & Load Balancing

- Shards represent **logical partitions** of your data (e.g., by domain or category).
- Each shard exists **once per cluster** (no replicas yet).
- Shards are distributed across index nodes.
- The Gateway maintains **per‑shard, per‑node document counts** and uses a **least‑loaded routing strategy** for indexing operations:
  - For a given `(shardId, document)` write/delete, the Gateway picks the index node **with the smallest document count for that shard**.
  - Over time, this keeps each shard **evenly balanced across index nodes** without a coordinator.
  - Counts are periodically snapshotted to disk so new Gateway instances can restore their view.

There is **no replication layer** yet. If an index node goes down, documents stored on that node’s shards are temporarily unavailable until the node comes back up and reloads its Lucene indices.

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
                 |
                 | gRPC QueryService
                 |
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

# Start a local cluster with 2 index nodes, 2 query nodes, and 1 gateway
make run-multi

# Cluster layout (by default):
#  - Index Nodes : 5000, 5001
#  - Query Nodes : 6000, 6001
#  - Gateway     : http://localhost:8080
```

### Example HTTP API Requests

#### Search Request

```json
{
  "query": "time travel romance",
  "page": 0,
  "pageSize": 10,
  "shardId": "movies",
  "searchType": "HYBRID",  // BM25 | SEMANTIC | HYBRID
  "fusionStrategy": "RRF" // SCORE_SUM | WEIGHTED | RRF
}
```

#### Index Request

```json
{
  "shardId": "movies",
  "id": "movie_001",
  "fields": {
    "title": "Interstellar",
    "content": "A team of explorers travel through a wormhole..."
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
indexNodes:
  routingStrategy: "LEAST_LOADED"
  componentLabel: "dsearch-index-node"
  nodes:
    - id: "0"
      host: "localhost"
      port: 5000
      healthPort: "5100"
    - id: "1"
      host: "localhost"
      port: 5001
      healthPort: "5101"

queryNodes:
  routingStrategy: "ROUND_ROBIN"
  componentLabel: "dsearch-query-node"
  nodes:
    - id: "0"
      host: "localhost"
      port: 6000
      healthPort: "6100"
    - id: "1"
      host: "localhost"
      port: 6001
      healthPort: "6101"

ml:
  models:
    textEmbedding:
      url: "djl://ai.djl.huggingface.pytorch/sentence-transformers/all-MiniLM-L6-v2"
      engine: "PyTorch"
```

- `indexNodes.routingStrategy` currently supports **`LEAST_LOADED`**, using per‑shard, per‑node doc counts.
- `queryNodes.routingStrategy` currently supports **`ROUND_ROBIN`** for fan‑out queries across multiple query node instances.

---

## Limitations & Roadmap

This project is intentionally minimal and educational. Some trade‑offs and potential future work:

- **No replication layer (yet)**
  - A shard lives on exactly one node; if that node goes down, its data is unavailable until restart.
  - Future direction: coordinator‑driven replication / Raft‑based shard groups.

- **Static shard assignment**
  - Shards are configured in `app-config.yaml`.
  - Future direction: dynamic shard reassignment and consistent hashing for smoother scaling.

- **Basic scoring and fusion**
  - BM25 + Lucene kNN + RRF fusion are implemented.
  - Future direction: learned ranking, per‑field boosts, filters, aggregations.

Despite these, `dsearch` is already a usable, horizontally scalable, Lucene‑backed search engine suitable for side projects, prototypes, and as a learning platform for distributed search architecture.

---

## License

This repository is intended as an educational and portfolio project.
This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.