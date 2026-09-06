package com.danieljhkim.dsearch.coordinator.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.enums.RoutingStrategy;
import com.danieljhkim.dsearch.coordinator.grpc.ClusterServiceImpl;
import com.danieljhkim.dsearch.proto.cluster.GetShardMapRequest;
import com.danieljhkim.dsearch.proto.cluster.GetShardMapResponse;
import io.grpc.stub.StreamObserver;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReplicaTopologyTest {

    @Test
    void shardMapExposesVersionedPrimaryReplicaRolesAndUnderReplication() {
        ClusterMembershipService membership = new ClusterMembershipService(config(), null, Clock.systemUTC());
        RecordingObserver<GetShardMapResponse> initial = new RecordingObserver<>();

        new ClusterServiceImpl(membership).getShardMap(GetShardMapRequest.getDefaultInstance(), initial);

        assertEquals(2, initial.value.getContractVersion());
        assertEquals(2, initial.value.getReplicationFactor());
        assertEquals("all", initial.value.getDurabilityPolicy());
        assertEquals(0, initial.value.getUnderReplicatedShards());
        assertEquals(6, initial.value.getShardLocationsCount());
        assertEquals(
                3,
                initial.value.getShardLocationsList().stream()
                        .filter(location -> location.getPrimary())
                        .count());
        assertTrue(initial.value.getShardLocationsList().stream()
                .allMatch(location -> location.getGeneration() == initial.value.getTopologyVersion()));

        long before = membership.getTopologyVersion();
        membership.removeNode("n0", com.danieljhkim.dsearch.proto.cluster.NodeRole.NODE_ROLE_INDEX);
        RecordingObserver<GetShardMapResponse> degraded = new RecordingObserver<>();
        new ClusterServiceImpl(membership).getShardMap(GetShardMapRequest.getDefaultInstance(), degraded);

        assertTrue(degraded.value.getTopologyVersion() > before);
        assertTrue(degraded.value.getUnderReplicatedShards() > 0);
        assertTrue(degraded.value.getShardLocationsList().stream()
                .anyMatch(location -> location.getNodeId().equals("n0") && !location.getEligible()));
    }

    private static AppConfig config() {
        AppConfig config = new AppConfig();
        config.setServiceDiscovery(new AppConfig.ServiceDiscoveryConfig());
        config.setIndexNodes(group(2, "n0", "n1", "n2"));
        config.setQueryNodes(group(1, "q0"));
        config.setCoordinatorNodes(group(1, "c0"));
        return config;
    }

    private static AppConfig.NodeGroupConfig group(int replicationFactor, String... ids) {
        AppConfig.NodeGroupConfig group = new AppConfig.NodeGroupConfig();
        group.setRoutingStrategy(RoutingStrategy.ROUND_ROBIN);
        group.setComponentLabel("test");
        group.setReplicationFactor(replicationFactor);
        group.setDurabilityPolicy("all");
        group.setReadConsistency("acknowledged");
        group.setNodes(List.of(ids).stream()
                .map(id -> {
                    AppConfig.NodeConfig node = new AppConfig.NodeConfig();
                    node.setId(id);
                    node.setHost("localhost");
                    node.setPort(5000 + Math.abs(id.hashCode() % 1000));
                    node.setHealthPort(6000 + Math.abs(id.hashCode() % 1000));
                    return node;
                })
                .toList());
        return group;
    }

    private static final class RecordingObserver<T> implements StreamObserver<T> {
        private T value;

        @Override
        public void onNext(T value) {
            this.value = value;
        }

        @Override
        public void onError(Throwable error) {
            throw new AssertionError(error);
        }

        @Override
        public void onCompleted() {}
    }
}
