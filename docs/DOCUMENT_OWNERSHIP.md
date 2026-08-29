# Document Ownership

Every document has exactly one authoritative index node. This page defines that
contract, what happens when the cluster changes shape, and what the gateway's
document counters do and do not mean.

## Why ownership exists

Indexing a document is a Lucene upsert (`IndexWriter.updateDocument` on the `id`
term). That upsert only replaces the document on the node that performs it. If
two nodes both accept a write for the same `id`, the cluster ends up holding two
copies of one logical document: the search fan-out returns both, hit counts are
inflated, and a delete that reaches only one of them appears to succeed while the
document is still searchable.

The gateway therefore never picks a node for a mutation based on load. It
computes the owner from the document key.

## The rule

```
owner(partitionId, documentId) = argmax over configured index nodes of
                                 hash64(nodeId, partitionId, documentId)
```

- Implemented by `DocumentOwnership` (`dk.common`), used through
  `NodeClientManager.ownerClient(partitionId, documentId)`.
- Rendezvous (highest-random-weight) hashing, FNV-1a 64 with a SplitMix64
  finalizer, ties broken by the lexicographically smallest node id.
- The result depends only on its inputs, so every gateway process reaches the
  same answer without coordinating and without keeping an assignment map.

### The ring is the configured node set

The candidate set is `indexNodes.nodes[].id` from `app-config.yaml`, captured
when the client manager is built. It is **not** the set of nodes that service
discovery currently reports as healthy.

This is the point of the design. Health is volatile; document placement is not.
If ownership tracked liveness, a node blipping out of the health registry would
hand its keys to a peer, the peer would accept an update for a document it does
not have, and the cluster would then hold two copies.

## Behavior

### Gateway restart

Ownership is recomputed from the config file and the document key, so it is
identical before and after a restart. Nothing about ownership is persisted,
because nothing needs to be.

Covered by `NodeClientManagerTest.ownershipSurvivesRebuildingTheManagerAsAfterAGatewayRestart`
and `DocumentOwnershipRoutingIntegrationTest.ownershipIsUnchangedWhenTheClusterIsRebuiltAsAfterARestart`.
`DocumentOwnershipTest.ownerIsPinnedToKnownValuesSoRestartsAndUpgradesKeepTheSameOwner`
pins concrete owners so a future change to the hash cannot silently strand
documents on their previous node.

### Node becomes unhealthy, or joins and leaves discovery

Ownership does not move. A node that is not currently active keeps its
documents, and mutations for those documents fail with
`NodeUnavailableException` (HTTP 503) rather than being rerouted. Reads are
unaffected: the search fan-out still uses only the active nodes.

A node discovered at runtime that is not in the configuration participates in
reads but never owns documents.

Covered by `NodeClientManagerTest.ownershipRingIsFixedAtConstructionSoDiscoveryChurnCannotMoveDocuments`
and `DocumentOwnershipRoutingIntegrationTest.mutationsForAnUnavailableOwnerAreRejectedRatherThanSentToThePeer`.

### Adding or removing an index node in configuration

Editing `indexNodes` changes the ring, and rendezvous hashing then reassigns
roughly `1/N` of the key space to or away from the changed node. Documents are
**not** moved by this change: a document whose owner changed stays physically on
its old node, where it remains searchable but is no longer updatable or
deletable through the gateway.

**Changing the configured index-node set requires a reindex.** There is no
rebalancing or handoff in the current runtime (the config already notes that
`replicationFactor` must stay at 1 for the same reason). `DocumentOwnershipTest`
pins the minimal-disruption property so the eventual rebalancer only has to move
that fraction of documents.

### Deleting a document that does not exist

Lucene delete-by-term is a no-op when nothing matches, and deletes are buffered,
so the owner cannot report whether anything was removed. A delete for an unknown
id is routed to the owner, returns success, and is idempotent.

### Document ids

The gateway mints the id when the client does not supply one. An id assigned
further downstream would be unknown to the ownership function, so a later update
of that document could be routed to a different node.

## Shard document counts

`NodeClient` keeps a per-`(node, partition)` counter, snapshotted to disk by the
gateway. It is now an approximate load and observability signal only - routing
no longer reads it.

- It is adjusted only after the owner confirms a mutation. A failed or rejected
  index/delete never changes it.
- Re-indexing an existing id increments it, so it overcounts updates: the owner
  cannot cheaply distinguish an insert from a replace under buffered writes.
- Deletes clamp at zero.

Sourcing these counts from each node's real Lucene document count would make
them exact; that is deferred.
