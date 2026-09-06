# Document Ownership and Replication

Every document has one authoritative primary range and a configurable set of replica
copies. This page defines placement, acknowledgement, failover, migration, and what the
gateway's document counters do and do not mean.

## Placement rule

Lucene upserts are local, so uncoordinated copies would make updates ambiguous and
inflate hits, document totals, and facets. Replication therefore creates explicit copies
of a logical primary range; it is never inferred from duplicate requests.

```
primary(partitionId, documentId) = argmax over configured index nodes of
                                   hash64(nodeId, partitionId, documentId)
```

`DocumentOwnership` implements this rendezvous-hash rule. `ReplicaPlacement` chooses
`replicationFactor - 1` distinct followers deterministically from the same configured
node set. Every range is named `index/<primaryNodeId>`, carries the coordinator topology
generation, and lists the primary first. Ties use the lexicographically smallest node id,
so every process computes the same result independent of input iteration order.

The candidate set is `indexNodes.nodes[].id` from configuration, captured when a client
manager is built. Health is volatile; placement is not. A runtime-discovered node outside
that configured set cannot accept replicated traffic until an explicit rebalance changes
the placement contract.

## Writes, idempotency, and fencing

Every replicated upsert and delete carries an `operation_id`, monotonic
`operation_generation`, placement generation, primary id, target id, and explicit
primary/replica role. The gateway commits the primary first and then its followers. Index
nodes durably retain the latest identity and generation per document:

- duplicate delivery of the same identity is an idempotent success;
- reordered operations and stale placement generations are rejected;
- a target mismatch is rejected, and a follower cannot accept a primary-role write;
- a primary outage rejects writes instead of allowing a client-side promotion;
- retrying the same operation after an uncertain response safely completes missing copies.

`indexNodes.durabilityPolicy` selects when the gateway may acknowledge:

- `one`: the primary commit. Followers may lag, so only `readConsistency: available` is
  valid.
- `quorum`: a majority of the configured replica set. A minority may lag, so only
  `readConsistency: available` is valid.
- `all`: every configured copy. This is required by `readConsistency: acknowledged`; any
  eligible failover copy then contains every acknowledged write.

The runtime rejects `readConsistency: acknowledged` with `one` or `quorum`, and rejects a
replication factor larger than the configured eligible-node set. A failure after the
primary commit but before the selected threshold produces no successful client
acknowledgement. A network partition therefore cannot create a writable second primary.

The mutation fence is stored per physical shard in Lucene commit user data. Lucene publishes
the document changes and the complete per-document identity/generation map through the same
checksummed `segments_N` commit point and durably syncs that commit before returning. A restart
therefore observes both the mutation and its fence or neither; there is no separately replaced
process-wide ledger that can lose another shard's update. Metadata format, entry count, encoded
identities, positive generations, and mutation types are validated at startup. Missing,
incomplete, conflicting, or unknown replication metadata prevents the index manager from
starting rather than serving without a fence. The former `replication-mutations.properties`
ledger is accepted only as a strictly validated upgrade source, committed into each shard, and
then removed.

If a replicated commit returns an uncertain failure, that shard is write-fenced for the lifetime
of the manager and its uncommitted writer state is rolled back on close. Restart resolves the
outcome from the latest valid Lucene commit. Automatic snapshot transfer, checksum comparison,
and convergence of a restored or lagging replica belong to the separate replica-repair workflow.

## Reads and failover

Query fanout selects the first eligible copy of each logical range, preferring its primary
and then deterministic followers. Exactly one copy of every range is queried, even when
several ranges fail over to the same physical node. Hits, total counts, and facets are
therefore aggregated once per logical shard rather than once per copy.

With `readConsistency: acknowledged` and `durabilityPolicy: all`, losing a primary does
not lose acknowledged writes. With `available`, a selected replica may omit writes that
were acknowledged under `one` or `quorum`; this is the declared consistency tradeoff.
Stale-generation replicas remain fenced from writes in either mode.

## Topology and observability

`GetShardMap` exposes placement generation, primary/replica role, eligibility,
acknowledgement policy, read consistency, and under-replicated count. `GET /cluster/health`
includes the bounded replication summary and failover count. Prometheus metrics cover
under-replication, failover, apply outcomes, missing acknowledgements, and
acknowledgement/apply latency without document- or shard-id labels.

Coordinator topology mutations increment the durable topology version. Restart accepts
the previous version-1 state format and rewrites it in version 2, retaining the existing
epoch/version monotonicity and node-lease fencing.

## Migration and cluster changes

`replicationFactor: 1` preserves the historical on-disk partition names and single-copy
behavior. For a factor above one, each logical primary range uses a distinct deterministic
physical partition on every replica. That isolation is what lets query fanout select one
copy without mixing ranges.

Existing single-copy partitions are not silently relabelled. Before increasing the
factor, reindex or restore every partition into the replicated layout, verify the admin
topology reports zero under-replicated ranges, and then switch clients. Rolling back to
factor one likewise requires reindexing into the historical layout.

Adding or removing an index node changes some primary ranges and follower sets. Use the
online handoff/rebalance workflow; editing configuration alone never moves Lucene data.
An unavailable follower remains explicitly under-replicated until repair completes.

## Restarts, deletes, ids, and counters

Placement is recomputed from configuration and the document key, so it is identical across
gateway restarts. A delete for a missing id is an idempotent success. The gateway mints a
document id before placement when the client omits one, ensuring later updates use the same
key.

`NodeClient` keeps approximate per-node, per-partition counters for observability. They are
adjusted only after a newly applied mutation is confirmed. An update can still overcount
because a remote node does not cheaply distinguish insert from replace; source exact counts
from Lucene when exact load telemetry is required.
