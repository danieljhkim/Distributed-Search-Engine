package com.danieljhkim.dsearch.common.shard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReplicaPlacementTest {

    @Test
    void placementIsDeterministicDistinctAndPrimaryFirst() {
        var first = ReplicaPlacement.forDocument(
                "tenant-a", "doc-1", List.of("n2", "n0", "n1"), 2, 17, ReplicaPlacement.DurabilityPolicy.ALL);
        var reordered = ReplicaPlacement.forDocument(
                "tenant-a", "doc-1", List.of("n1", "n2", "n0"), 2, 17, ReplicaPlacement.DurabilityPolicy.ALL);

        assertEquals(first, reordered);
        assertEquals(first.primaryNodeId(), first.nodeIds().getFirst());
        assertEquals(2, first.nodeIds().stream().distinct().count());
        assertEquals(2, first.requiredAcknowledgements());
    }

    @Test
    void failoverSelectsOneEligibleCopyOfEveryLogicalShard() {
        List<ReplicaPlacement.ReadTarget> targets = ReplicaPlacement.readPlan(
                        "tenant-a",
                        List.of("n0", "n1", "n2"),
                        List.of("n1", "n2"),
                        2,
                        9,
                        ReplicaPlacement.DurabilityPolicy.ALL)
                .targets();

        assertEquals(3, targets.size());
        assertEquals(
                3,
                targets.stream()
                        .map(ReplicaPlacement.ReadTarget::logicalShardId)
                        .distinct()
                        .count());
        assertTrue(
                targets.stream().anyMatch(target -> target.logicalShardId().equals("index/n0") && target.failover()));
        assertEquals(
                3,
                targets.stream()
                        .map(ReplicaPlacement.ReadTarget::storagePartitionId)
                        .distinct()
                        .count());
    }

    @Test
    void readPlanRetainsUnavailableLogicalRangesWhenOnlyOneReplicaIsActive() {
        ReplicaPlacement.ReadPlan plan = ReplicaPlacement.readPlan(
                "tenant-a", List.of("n0", "n1", "n2"), List.of("n1"), 2, 9, ReplicaPlacement.DurabilityPolicy.ALL);

        assertEquals(
                3, plan.targets().size() + plan.unavailableLogicalShardIds().size());
        assertEquals(
                List.of("index/n0", "index/n1", "index/n2"),
                java.util.stream.Stream.concat(
                                plan.targets().stream().map(ReplicaPlacement.ReadTarget::logicalShardId),
                                plan.unavailableLogicalShardIds().stream())
                        .sorted()
                        .toList());
        assertEquals(1, plan.targets().size());
        assertEquals("index/n1", plan.targets().getFirst().logicalShardId());
        assertEquals(List.of("index/n0", "index/n2"), plan.unavailableLogicalShardIds());
    }

    @Test
    void singleCopyKeepsHistoricalPartitionWhileReplicasUseIsolatedStorage() {
        assertEquals("tenant-a", ReplicaPlacement.storagePartitionId("tenant-a", "n0", 1));
        assertNotEquals(
                ReplicaPlacement.storagePartitionId("tenant-a", "n0", 2),
                ReplicaPlacement.storagePartitionId("tenant-a", "n1", 2));
    }

    @Test
    void unsafeReplicaFactorIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReplicaPlacement.forDocument(
                        "tenant-a", "doc-1", List.of("n0"), 2, 1, ReplicaPlacement.DurabilityPolicy.ALL));
    }

    @Test
    void acknowledgementPoliciesHaveExplicitThresholds() {
        assertEquals(1, ReplicaPlacement.DurabilityPolicy.ONE.requiredAcknowledgements(3));
        assertEquals(2, ReplicaPlacement.DurabilityPolicy.QUORUM.requiredAcknowledgements(3));
        assertEquals(3, ReplicaPlacement.DurabilityPolicy.ALL.requiredAcknowledgements(3));
    }
}
