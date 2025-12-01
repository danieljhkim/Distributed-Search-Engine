# Distributed Search Engine (dsearch) 

This project contains horizontally scalable, easily maintained, barebones, Lucene-based distributed search engine built in Java.

It has three primary components:
- Gateway Node – HTTP API entrypoint (Spring Boot)
- Query Nodes – Fan-out and merge layer (gRPC)
- Index Nodes – Storage + Lucene shard search (gRPC)

Each index node can host multiple Lucene index shards (for example: `shard-movies`, `shard-shows`, etc.).  
Each shard represents a **categorical or domain-specific segmentation** of your data.

These shards may live on **multiple index nodes simultaneously**—Query Nodes will **fan‑out searches for a given shard/category to all index nodes that host that shard**, gather the partial results, and merge them into a final ranked response.

This architecture lets you scale horizontally simply by adding more index nodes and assigning shards to them. No rebalancing is required.

You can easily add more index nodes on the fly to increase capacity and distribute load without any re-balancing.
Once new index nodes are added, gateway component will distribute indexing (write) requests evenly, round-robin style.
It's a simpler approach for simpler projects, requiring minimal server instances. 

Note that there is no replication layer in this design, so if an index node goes down, the shards on that node will be temporarily unavailable until the node is back up.

### Documentation

- [Quick Start Guid](./docs/QUICKSTART.md)
- [Benchmarks](./docs/BENCHMARKS.md)


--- 

## Architecture Overview

```
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

The search engine supports **two complementary retrieval modes**:

1. **Lexical Search (BM25)** – classic keyword relevance
2. **Semantic Search (Embeddings + Lucene kNN)** – retrieves results by *meaning*, not words

Both retrieval modes can also be **combined into a hybrid ranking**.

---

### 1. Document Indexing
Each indexed document:
- Concatenates all textual fields into a single representation
- Generates a dense embedding using the transformer model (`all-MiniLM-L6-v2`)
- Stores in Lucene:
    - **BM25 text field** for keyword matching
    - **Vector field** (`KnnVectorField`) for semantic similarity search

This allows the engine to perform keyword, semantic, or hybrid retrieval on the same content.

---

### 2. Query Execution Modes

#### **A. BM25 Search (Keyword)**
- Lucene processes the query using BM25
- Best for exact keywords, short queries, filters
- Low-latency and highly precise

#### **B. Semantic Search (Embedding kNN)**
- Query text is embedded using the same transformer model
- Query Node fans out to all Index Nodes that contain the target shard/category
- Lucene performs HNSW-based vector similarity search
- Useful for natural-language queries and conceptual similarity

#### **C. Hybrid Search (BM25 + Semantic Fusion)**
To combine both approaches:

1. Run BM25 search → top K
2. Run semantic kNN search → top K
3. Merge results (by doc ID)
4. Apply a fusion strategy:
    - **score_sum:** bm25Score + semanticScore
    - **weighted:** α·bm25 + β·semantic
    - **RRF (Reciprocal Rank Fusion):** rank-based merging
5. Return the merged, re-ranked list

---

## Example API Requests

### Search Request
```json
{
  "query": "time travel romance",
  "page": 0,
  "pageSize": 10,
  "shardId": "movies",
  "searchType": "SEMANTIC" // BM25 | SEMANTIC | HYBRID
}
```

### Index Request
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

## Configuration

The cluster configuration is defined in `app-config.yaml`:

```yaml
cluster:
    indexNodes:
    - id: 0
      host: "localhost"
      port: 6000
    - id: 1
      host: "localhost"
      port: 6001

query-node:
  client:
    host: "localhost"
    ports: [5000, 5001]

index-node:
  client:
    host: "localhost"
    ports: [6000, 6001]

ml:
  models:
    textEmbedding:
      url: "djl://ai.djl.huggingface.pytorch/sentence-transformers/all-MiniLM-L6-v2"
      engine: "PyTorch"
```