package com.danieljhkim.dsearch.common.shard;

import com.danieljhkim.dsearch.common.routing.DocumentOwnership;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Deterministic logical-shard placement shared by coordinators, gateways, and query nodes. */
public final class ReplicaPlacement {

    private static final int MAX_PARTITION_ID_LENGTH = 64;
    private static final int STORAGE_HASH_LENGTH = 16;

    private ReplicaPlacement() {}

    public enum DurabilityPolicy {
        ONE,
        QUORUM,
        ALL;

        public static DurabilityPolicy parse(String value) {
            return value == null || value.isBlank()
                    ? ALL
                    : valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        }

        public int requiredAcknowledgements(int replicaCount) {
            if (replicaCount < 1) {
                throw new IllegalArgumentException("replicaCount must be positive");
            }
            return switch (this) {
                case ONE -> 1;
                case QUORUM -> (replicaCount / 2) + 1;
                case ALL -> replicaCount;
            };
        }
    }

    public enum ReadConsistency {
        AVAILABLE,
        ACKNOWLEDGED;

        public static ReadConsistency parse(String value) {
            return value == null || value.isBlank()
                    ? ACKNOWLEDGED
                    : valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        }
    }

    public record ReplicaSet(
            String shardId, long generation, String primaryNodeId, List<String> nodeIds, int requiredAcknowledgements) {
        public ReplicaSet {
            Objects.requireNonNull(shardId, "shardId must not be null");
            Objects.requireNonNull(primaryNodeId, "primaryNodeId must not be null");
            nodeIds = List.copyOf(nodeIds);
            if (generation < 1) {
                throw new IllegalArgumentException("generation must be positive");
            }
            if (nodeIds.isEmpty() || !primaryNodeId.equals(nodeIds.get(0))) {
                throw new IllegalArgumentException("primary must be the first replica");
            }
            if (new HashSet<>(nodeIds).size() != nodeIds.size()) {
                throw new IllegalArgumentException("replicas must be distinct");
            }
            if (requiredAcknowledgements < 1 || requiredAcknowledgements > nodeIds.size()) {
                throw new IllegalArgumentException("required acknowledgements must fit the replica set");
            }
        }
    }

    public record ReadTarget(String logicalShardId, String nodeId, String storagePartitionId, boolean failover) {}

    public static ReplicaSet forDocument(
            String partitionId,
            String documentId,
            Collection<String> eligibleNodeIds,
            int replicationFactor,
            long generation,
            DurabilityPolicy durabilityPolicy) {
        List<String> nodes = normalizedNodes(eligibleNodeIds, replicationFactor);
        String primary = DocumentOwnership.ownerNodeId(partitionId, documentId, nodes);
        return forPrimary(partitionId, primary, nodes, replicationFactor, generation, durabilityPolicy);
    }

    public static ReplicaSet forPrimary(
            String partitionId,
            String primaryNodeId,
            Collection<String> eligibleNodeIds,
            int replicationFactor,
            long generation,
            DurabilityPolicy durabilityPolicy) {
        Objects.requireNonNull(partitionId, "partitionId must not be null");
        Objects.requireNonNull(primaryNodeId, "primaryNodeId must not be null");
        Objects.requireNonNull(durabilityPolicy, "durabilityPolicy must not be null");
        List<String> nodes = normalizedNodes(eligibleNodeIds, replicationFactor);
        if (!nodes.contains(primaryNodeId)) {
            throw new IllegalArgumentException("primary is not an eligible node: " + primaryNodeId);
        }

        List<String> followers = nodes.stream()
                .filter(nodeId -> !nodeId.equals(primaryNodeId))
                .sorted(replicaOrder(partitionId, primaryNodeId))
                .limit(replicationFactor - 1L)
                .toList();
        List<String> replicas = new ArrayList<>(replicationFactor);
        replicas.add(primaryNodeId);
        replicas.addAll(followers);
        return new ReplicaSet(
                logicalShardId(primaryNodeId),
                Math.max(1L, generation),
                primaryNodeId,
                replicas,
                durabilityPolicy.requiredAcknowledgements(replicas.size()));
    }

    public static List<ReadTarget> readTargets(
            String partitionId,
            Collection<String> eligibleNodeIds,
            Collection<String> activeNodeIds,
            int replicationFactor,
            long generation,
            DurabilityPolicy durabilityPolicy) {
        List<String> nodes = normalizedNodes(eligibleNodeIds, replicationFactor);
        Set<String> active = Set.copyOf(activeNodeIds);
        List<ReadTarget> targets = new ArrayList<>(nodes.size());
        for (String primary : nodes) {
            ReplicaSet set = forPrimary(partitionId, primary, nodes, replicationFactor, generation, durabilityPolicy);
            set.nodeIds().stream()
                    .filter(active::contains)
                    .findFirst()
                    .ifPresent(selected -> targets.add(new ReadTarget(
                            set.shardId(),
                            selected,
                            storagePartitionId(partitionId, primary, replicationFactor),
                            !selected.equals(primary))));
        }
        return List.copyOf(targets);
    }

    public static String logicalShardId(String primaryNodeId) {
        return "index/" + primaryNodeId;
    }

    /**
     * Replication factor one retains the historical on-disk layout. Replicated layouts isolate each
     * logical primary range so query fanout can select exactly one copy without double counting.
     */
    public static String storagePartitionId(String partitionId, String primaryNodeId, int replicationFactor) {
        Objects.requireNonNull(partitionId, "partitionId must not be null");
        Objects.requireNonNull(primaryNodeId, "primaryNodeId must not be null");
        if (replicationFactor <= 1) {
            return partitionId;
        }
        String hash = Long.toUnsignedString(DocumentOwnership.score(primaryNodeId, partitionId, "replica"), 16);
        hash = "0".repeat(Math.max(0, STORAGE_HASH_LENGTH - hash.length())) + hash;
        int prefixLength = MAX_PARTITION_ID_LENGTH - STORAGE_HASH_LENGTH - 2;
        return partitionId.substring(0, Math.min(prefixLength, partitionId.length())) + "_r" + hash;
    }

    private static List<String> normalizedNodes(Collection<String> nodeIds, int replicationFactor) {
        Objects.requireNonNull(nodeIds, "eligibleNodeIds must not be null");
        List<String> nodes = nodeIds.stream()
                .map(node -> Objects.requireNonNull(node, "node id"))
                .sorted()
                .toList();
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("at least one eligible node is required");
        }
        if (new HashSet<>(nodes).size() != nodes.size()) {
            throw new IllegalArgumentException("eligible node ids must be distinct");
        }
        if (replicationFactor < 1 || replicationFactor > nodes.size()) {
            throw new IllegalArgumentException(
                    "replicationFactor must be between 1 and the eligible node count (" + nodes.size() + ")");
        }
        return nodes;
    }

    private static Comparator<String> replicaOrder(String partitionId, String primaryNodeId) {
        return (left, right) -> {
            long leftScore = DocumentOwnership.score(left, partitionId, "replica:" + primaryNodeId);
            long rightScore = DocumentOwnership.score(right, partitionId, "replica:" + primaryNodeId);
            int scoreOrder = Long.compare(rightScore, leftScore);
            return scoreOrder != 0 ? scoreOrder : left.compareTo(right);
        };
    }
}
