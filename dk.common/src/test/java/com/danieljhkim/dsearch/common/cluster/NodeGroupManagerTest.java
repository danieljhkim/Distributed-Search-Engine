package com.danieljhkim.dsearch.common.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.enums.RoutingStrategy;
import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.proto.cluster.ClusterServiceGrpc;
import com.danieljhkim.dsearch.proto.cluster.GetClusterInfoResponse;
import com.danieljhkim.dsearch.proto.cluster.NodeInfo;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import io.grpc.Status;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class NodeGroupManagerTest {

    @Test
    void discoveryFailureBeforeFirstResponseFailsClosedInsteadOfUsingStaticNodes() {
        MutableClock clock = new MutableClock();
        NodeGroupManager manager = managerWithResponses(clock, 5, Status.UNAVAILABLE.asRuntimeException());

        assertThrows(IllegalStateException.class, () -> manager.getNodeGroup(NodeRole.NODE_ROLE_INDEX));
    }

    @Test
    void temporaryCoordinatorOutageUsesOnlyBoundedAcceptedTopology() {
        MutableClock clock = new MutableClock();
        NodeGroupManager manager = managerWithResponses(
                clock,
                5,
                topology("epoch-a", 2, "dynamic-0"),
                Status.UNAVAILABLE.asRuntimeException(),
                Status.UNAVAILABLE.asRuntimeException());

        NodeGroup accepted = manager.getNodeGroup(NodeRole.NODE_ROLE_INDEX);
        assertEquals(List.of("dynamic-0"), nodeIds(accepted));

        clock.advanceSeconds(4);
        assertEquals(List.of("dynamic-0"), nodeIds(manager.getNodeGroup(NodeRole.NODE_ROLE_INDEX)));

        clock.advanceSeconds(2);
        assertThrows(IllegalStateException.class, () -> manager.getNodeGroup(NodeRole.NODE_ROLE_INDEX));
    }

    @Test
    void regressedCoordinatorVersionIsRejectedAndCannotRefreshTheStalenessWindow() {
        MutableClock clock = new MutableClock();
        NodeGroupManager manager =
                managerWithResponses(clock, 0, topology("epoch-a", 5, "dynamic-0"), topology("epoch-a", 4, "static-0"));

        assertEquals(List.of("dynamic-0"), nodeIds(manager.getNodeGroup(NodeRole.NODE_ROLE_INDEX)));
        clock.advanceSeconds(1);

        assertThrows(IllegalStateException.class, () -> manager.getNodeGroup(NodeRole.NODE_ROLE_INDEX));
    }

    @Test
    void staticModeUsesConfiguredGroupsAndCoordinatorDoesNotNeedDiscovery() {
        AppConfig config = appConfig(0);
        config.getServiceDiscovery().setEnabled(false);
        NodeGroupManager manager = new NodeGroupManager(config);

        assertEquals(List.of("static-0"), nodeIds(manager.getNodeGroup(NodeRole.NODE_ROLE_INDEX)));
        assertEquals(List.of("coordinator-0"), nodeIds(manager.getNodeGroup(NodeRole.NODE_ROLE_COORDINATOR)));
        assertEquals(List.of("static-q0"), nodeIds(manager.getConfiguredNodeGroup(NodeRole.NODE_ROLE_QUERY)));
        assertEquals(false, manager.hasCoordinatorManager());
    }

    @Test
    void discoveryWithoutCoordinatorFailsClosedAndInvalidResponsesAreRejected() {
        AppConfig config = appConfig(5);
        NodeGroupManager withoutCoordinator = new NodeGroupManager(config);
        assertThrows(IllegalStateException.class, () -> withoutCoordinator.getNodeGroup(NodeRole.NODE_ROLE_INDEX));

        NodeGroupManager badContract = managerWithResponses(new MutableClock(), 5, topologyWithContract(2));
        assertThrows(IllegalStateException.class, () -> badContract.getNodeGroup(NodeRole.NODE_ROLE_INDEX));
        NodeGroupManager blankEpoch = managerWithResponses(new MutableClock(), 5, topology("", 1, "dynamic-0"));
        assertThrows(IllegalStateException.class, () -> blankEpoch.getNodeGroup(NodeRole.NODE_ROLE_INDEX));
        MutableClock epochClock = new MutableClock();
        NodeGroupManager epochChanged = managerWithResponses(
                epochClock, 5, topology("epoch-a", 1, "dynamic-0"), topology("epoch-b", 2, "dynamic-1"));
        epochChanged.getNodeGroup(NodeRole.NODE_ROLE_INDEX);
        assertEquals(List.of("dynamic-0"), nodeIds(epochChanged.getNodeGroup(NodeRole.NODE_ROLE_INDEX)));
        epochClock.advanceSeconds(6);
        assertThrows(IllegalStateException.class, () -> epochChanged.getNodeGroup(NodeRole.NODE_ROLE_INDEX));
    }

    @SafeVarargs
    private static NodeGroupManager managerWithResponses(
            MutableClock clock, int maxStalenessSeconds, Object... responses) {
        AppConfig config = appConfig(maxStalenessSeconds);
        NodeGroupManager groupManager = new NodeGroupManager(config, clock);
        @SuppressWarnings("unchecked")
        NodeClientManager<ClusterServiceGrpc.ClusterServiceBlockingStub> clientManager = mock(NodeClientManager.class);
        ClusterServiceGrpc.ClusterServiceBlockingStub stub = mock(ClusterServiceGrpc.ClusterServiceBlockingStub.class);
        when(clientManager.nextClient()).thenReturn(stub);

        org.mockito.stubbing.OngoingStubbing<GetClusterInfoResponse> stubbing = when(stub.getClusterInfo(any()));
        for (Object response : responses) {
            if (response instanceof GetClusterInfoResponse value) {
                stubbing = stubbing.thenReturn(value);
            } else if (response instanceof RuntimeException error) {
                stubbing = stubbing.thenThrow(error);
            } else {
                throw new IllegalArgumentException("Unsupported mock response: " + response);
            }
        }
        groupManager.setCoordinatorManager(clientManager);
        return groupManager;
    }

    private static GetClusterInfoResponse topology(String epoch, long version, String nodeId) {
        return GetClusterInfoResponse.newBuilder()
                .setContractVersion(1)
                .setTopologyEpoch(epoch)
                .setTopologyVersion(version)
                .setComponentLabel("authoritative-index")
                .setRoutingStrategy(RoutingStrategy.ROUND_ROBIN.name())
                .setReplicationFactor(1)
                .addNodes(NodeInfo.newBuilder()
                        .setNodeId(nodeId)
                        .setHost("localhost")
                        .setPort(5000)
                        .setHealthPort(5100)
                        .setRole(NodeRole.NODE_ROLE_INDEX)
                        .build())
                .build();
    }

    private static GetClusterInfoResponse topologyWithContract(int contractVersion) {
        return topology("epoch-a", 1, "dynamic-0").toBuilder()
                .setContractVersion(contractVersion)
                .build();
    }

    private static List<String> nodeIds(NodeGroup group) {
        return group.getAllNodes().stream()
                .map(NodeGroup.NodeInfo::getNodeId)
                .sorted()
                .toList();
    }

    private static AppConfig appConfig(int maxStalenessSeconds) {
        AppConfig config = new AppConfig();
        AppConfig.ServiceDiscoveryConfig discovery = new AppConfig.ServiceDiscoveryConfig();
        discovery.setEnabled(true);
        discovery.setMaxStalenessSeconds(maxStalenessSeconds);
        config.setServiceDiscovery(discovery);
        config.setIndexNodes(nodeGroup("static-0", 5000));
        config.setQueryNodes(nodeGroup("static-q0", 6000));
        config.setCoordinatorNodes(nodeGroup("coordinator-0", 7000));
        return config;
    }

    private static AppConfig.NodeGroupConfig nodeGroup(String nodeId, int port) {
        AppConfig.NodeConfig node = new AppConfig.NodeConfig();
        node.setId(nodeId);
        node.setHost("localhost");
        node.setPort(port);
        node.setHealthPort(port + 100);
        AppConfig.NodeGroupConfig group = new AppConfig.NodeGroupConfig();
        group.setComponentLabel("test");
        group.setRoutingStrategy(RoutingStrategy.ROUND_ROBIN);
        group.setNodes(List.of(node));
        return group;
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-08-29T00:00:00Z");

        private void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
