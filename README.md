# Easy-Breezy Distributed Search Engine 

This project contains horizontally scalable, easily maintained, barebones, Lucene-based distributed search engine built in Java.

It has three primary components:
- Gateway Node – HTTP API entrypoint (Spring Boot)
- Query Nodes – Fan-out and merge layer (gRPC)
- Index Nodes – Storage + Lucene shard search (gRPC)

Each index nodes could contain multiple lucene index shards, i.e. shard-movies, shard-shows, etc.
So each shard here represents a categorical segmentation of your data.
And these categorical segmentations are distributed across multiple index nodes for scalability; query nodes will fan-out search requests on that category to all index nodes and merge the results.

You can easily add more index nodes on the fly to increase capacity and distribute load without any re-balancing.
Once new index nodes are added, gateway component will distribute indexing (write) requests evenly, round-robin style.
It's a simpler approach for simpler projects, requiring minimal server instances. 

Note that there is no replication layer in this design, so if an index node goes down, the shards on that node will be temporarily unavailable until the node is back up.

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


## Configuration

The cluster configuration is defined in `cluster-config.yaml`:

```yaml
cluster:
    indexShards:
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
```