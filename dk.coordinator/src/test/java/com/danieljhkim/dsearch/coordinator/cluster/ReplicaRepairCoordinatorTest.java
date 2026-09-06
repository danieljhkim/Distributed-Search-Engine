package com.danieljhkim.dsearch.coordinator.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.danieljhkim.dsearch.proto.cluster.ReplicaRepairState;
import com.danieljhkim.dsearch.proto.index.ReplicaManifest;
import org.junit.jupiter.api.Test;

class ReplicaRepairCoordinatorTest {

    @Test
    void classifiesEveryDivergenceWithoutTrustingProcessHealth() {
        ReplicaManifest source = manifest(9, 42, "good");

        assertEquals(ReplicaRepairState.REPLICA_REPAIR_STATE_MISSING, ReplicaRepairCoordinator.classify(source, null));
        assertEquals(
                ReplicaRepairState.REPLICA_REPAIR_STATE_WRONG_GENERATION,
                ReplicaRepairCoordinator.classify(source, manifest(8, 42, "good")));
        assertEquals(
                ReplicaRepairState.REPLICA_REPAIR_STATE_LAGGING,
                ReplicaRepairCoordinator.classify(source, manifest(9, 41, "old")));
        assertEquals(
                ReplicaRepairState.REPLICA_REPAIR_STATE_CHECKSUM_DIVERGENT,
                ReplicaRepairCoordinator.classify(source, manifest(9, 42, "corrupt")));
        assertEquals(
                ReplicaRepairState.REPLICA_REPAIR_STATE_READY,
                ReplicaRepairCoordinator.classify(source, manifest(9, 42, "good")));
    }

    private static ReplicaManifest manifest(long generation, long position, String checksum) {
        return ReplicaManifest.newBuilder()
                .setShardId("tenant_r1")
                .setLogicalPartitionId("tenant")
                .setPrimaryNodeId("n0")
                .setPlacementGeneration(generation)
                .setCommittedPosition(position)
                .setContentChecksum(checksum)
                .build();
    }
}
