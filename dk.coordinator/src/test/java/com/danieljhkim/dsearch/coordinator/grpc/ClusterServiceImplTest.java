package com.danieljhkim.dsearch.coordinator.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.danieljhkim.dsearch.common.cluster.NodeGroup;
import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.enums.RoutingStrategy;
import com.danieljhkim.dsearch.coordinator.cluster.ClusterMembershipService;
import com.danieljhkim.dsearch.proto.cluster.GetClusterInfoRequest;
import com.danieljhkim.dsearch.proto.cluster.GetClusterInfoResponse;
import com.danieljhkim.dsearch.proto.cluster.GetShardMapRequest;
import com.danieljhkim.dsearch.proto.cluster.GetShardMapResponse;
import com.danieljhkim.dsearch.proto.cluster.HeartbeatRequest;
import com.danieljhkim.dsearch.proto.cluster.HeartbeatResponse;
import com.danieljhkim.dsearch.proto.cluster.NodeInfo;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import com.danieljhkim.dsearch.proto.cluster.RegisterNodeRequest;
import com.danieljhkim.dsearch.proto.cluster.RegisterNodeResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClusterServiceImplTest {

    @Test
    void registerIndexNodeAndClusterInfoIncludesNodeAndGroupData() {
        ClusterMembershipService membershipService = new ClusterMembershipService(appConfig());
        ClusterServiceImpl service = new ClusterServiceImpl(membershipService);

        RegisterNodeResponse registerResponse = registerNode(
                service, registerRequest("index-live-0", "index-live.local", 5011, 5111, NodeRole.NODE_ROLE_INDEX));

        assertTrue(registerResponse.getSuccess());
        GetClusterInfoResponse clusterInfo = getClusterInfo(service, NodeRole.NODE_ROLE_INDEX);
        assertEquals("index-nodes", clusterInfo.getComponentLabel());
        assertEquals(RoutingStrategy.ROUND_ROBIN.name(), clusterInfo.getRoutingStrategy());
        assertEquals(1, clusterInfo.getReplicationFactor());
        assertNode(
                findNode(clusterInfo, "index-live-0"),
                "index-live-0",
                "index-live.local",
                5011,
                5111,
                NodeRole.NODE_ROLE_INDEX);
    }

    @Test
    void registerQueryNodeAndClusterInfoIncludesNodeAndGroupData() {
        ClusterMembershipService membershipService = new ClusterMembershipService(appConfig());
        ClusterServiceImpl service = new ClusterServiceImpl(membershipService);

        RegisterNodeResponse registerResponse = registerNode(
                service, registerRequest("query-live-0", "query-live.local", 6011, 6111, NodeRole.NODE_ROLE_QUERY));

        assertTrue(registerResponse.getSuccess());
        GetClusterInfoResponse clusterInfo = getClusterInfo(service, NodeRole.NODE_ROLE_QUERY);
        assertEquals("query-nodes", clusterInfo.getComponentLabel());
        assertEquals(RoutingStrategy.LEAST_LOADED.name(), clusterInfo.getRoutingStrategy());
        assertEquals(1, clusterInfo.getReplicationFactor());
        assertNode(
                findNode(clusterInfo, "query-live-0"),
                "query-live-0",
                "query-live.local",
                6011,
                6111,
                NodeRole.NODE_ROLE_QUERY);
    }

    @Test
    void duplicateRegistrationUpdatesExistingNode() {
        ClusterServiceImpl service = new ClusterServiceImpl(new ClusterMembershipService(appConfig()));

        RegisterNodeResponse firstResponse = registerNode(
                service, registerRequest("index-live-0", "old.local", 5011, 5111, NodeRole.NODE_ROLE_INDEX));
        RegisterNodeResponse secondResponse = registerNode(
                service, registerRequest("index-live-0", "new.local", 5022, 5122, NodeRole.NODE_ROLE_INDEX));

        assertTrue(firstResponse.getSuccess());
        assertTrue(secondResponse.getSuccess());
        GetClusterInfoResponse clusterInfo = getClusterInfo(service, NodeRole.NODE_ROLE_INDEX);
        assertEquals(1, clusterInfo.getNodesCount());
        assertNode(
                findNode(clusterInfo, "index-live-0"),
                "index-live-0",
                "new.local",
                5022,
                5122,
                NodeRole.NODE_ROLE_INDEX);
    }

    @Test
    void registerNodeStoresValidNodeInMembership() {
        ClusterMembershipService membershipService = new ClusterMembershipService(appConfig());
        ClusterServiceImpl service = new ClusterServiceImpl(membershipService);
        CapturingObserver<RegisterNodeResponse> observer = new CapturingObserver<>();

        service.registerNode(validRegisterRequest().build(), observer);

        assertNull(observer.error);
        assertTrue(observer.completed);
        assertTrue(observer.value.getSuccess());
        assertNotNull(membershipService.getIndexGroup().getNode("node-a"));
    }

    @Test
    void registerNodeRejectsInvalidRole() {
        RegisterNodeRequest request =
                validRegisterRequest().setRole(NodeRole.NODE_ROLE_UNKNOWN).build();

        assertRegisterStatus(request, Status.Code.INVALID_ARGUMENT, "role must be INDEX, QUERY, or COORDINATOR");
    }

    @Test
    void registerNodeRejectsEmptyNodeId() {
        RegisterNodeRequest request = validRegisterRequest().setNodeId("").build();

        assertRegisterStatus(request, Status.Code.INVALID_ARGUMENT, "node_id must not be empty");
    }

    @Test
    void registerNodeRejectsInvalidHost() {
        RegisterNodeRequest request = validRegisterRequest().setHost("bad host").build();

        assertRegisterStatus(request, Status.Code.INVALID_ARGUMENT, "host must be a valid DNS name or IP literal");
    }

    @Test
    void registerNodeRejectsInvalidPort() {
        RegisterNodeRequest request = validRegisterRequest().setPort(0).build();

        assertRegisterStatus(request, Status.Code.INVALID_ARGUMENT, "port must be between 1 and 65535");
    }

    @Test
    void registerNodeRejectsInvalidHealthPort() {
        RegisterNodeRequest request =
                validRegisterRequest().setHealthPort(70000).build();

        assertRegisterStatus(request, Status.Code.INVALID_ARGUMENT, "health_port must be between 1 and 65535");
    }

    @Test
    void registerNodeReturnsNotFoundForMissingNodeGroup() {
        ClusterMembershipService membershipService = new ClusterMembershipService(appConfig()) {
            @Override
            public NodeGroup resolveGroup(NodeRole role) {
                return null;
            }

            @Override
            public void registerNode(NodeGroup.NodeInfo nodeInfo, NodeRole role) {
                fail("registerNode should not be called when the node group is missing");
            }
        };
        ClusterServiceImpl service = new ClusterServiceImpl(membershipService);
        CapturingObserver<RegisterNodeResponse> observer = new CapturingObserver<>();

        service.registerNode(validRegisterRequest().build(), observer);

        assertStatus(observer.error, Status.Code.NOT_FOUND, "No node group registered for role: NODE_ROLE_INDEX");
    }

    @Test
    void getClusterInfoRejectsInvalidRole() {
        ClusterServiceImpl service = new ClusterServiceImpl(new ClusterMembershipService(appConfig()));
        CapturingObserver<GetClusterInfoResponse> observer = new CapturingObserver<>();
        GetClusterInfoRequest request = GetClusterInfoRequest.newBuilder()
                .setRole(NodeRole.NODE_ROLE_UNKNOWN)
                .build();

        service.getClusterInfo(request, observer);

        assertStatus(observer.error, Status.Code.INVALID_ARGUMENT, "role must be INDEX, QUERY, or COORDINATOR");
    }

    @Test
    void clusterInfoOmitsNodesMarkedUnhealthyWithoutSleeping() {
        ClusterMembershipService membershipService = new ClusterMembershipService(appConfig());
        ClusterServiceImpl service = new ClusterServiceImpl(membershipService);
        registerNode(
                service, registerRequest("query-live-0", "query-live.local", 6011, 6111, NodeRole.NODE_ROLE_QUERY));

        membershipService.updateNodeHealth("query-live-0", NodeRole.NODE_ROLE_QUERY, false);

        GetClusterInfoResponse clusterInfo = getClusterInfo(service, NodeRole.NODE_ROLE_QUERY);
        assertFalse(clusterInfo.getNodesList().stream()
                .anyMatch(node -> node.getNodeId().equals("query-live-0")));
    }

    @Test
    void heartbeatRenewsRegisteredNodeLeaseAndReturnsVersionedContract() {
        ClusterMembershipService membershipService = new ClusterMembershipService(appConfig());
        ClusterServiceImpl service = new ClusterServiceImpl(membershipService);
        CapturingObserver<HeartbeatResponse> observer = new CapturingObserver<>();
        registerNode(service, validRegisterRequest().build());
        long registeredVersion = membershipService.getTopologyVersion();

        service.heartbeat(
                HeartbeatRequest.newBuilder()
                        .setNodeId("node-a")
                        .setRole(NodeRole.NODE_ROLE_INDEX)
                        .setObservedTopologyVersion(membershipService.getTopologyVersion())
                        .build(),
                observer);

        assertNull(observer.error);
        assertTrue(observer.completed);
        assertTrue(observer.value.getSuccess());
        assertEquals(ClusterMembershipService.CONTRACT_VERSION, observer.value.getContractVersion());
        assertEquals(membershipService.getTopologyEpoch(), observer.value.getTopologyEpoch());
        assertEquals(registeredVersion, observer.value.getTopologyVersion());
        assertTrue(observer.value.getLeaseDurationMillis() > 0);
    }

    @Test
    void shardMapReturnsDeterministicVersionedIndexPlacement() {
        ClusterMembershipService membershipService = new ClusterMembershipService(appConfig());
        ClusterServiceImpl service = new ClusterServiceImpl(membershipService);
        CapturingObserver<GetShardMapResponse> observer = new CapturingObserver<>();
        registerNode(service, registerRequest("z-node", "z.local", 5002, 5102, NodeRole.NODE_ROLE_INDEX));
        registerNode(service, registerRequest("a-node", "a.local", 5001, 5101, NodeRole.NODE_ROLE_INDEX));

        service.getShardMap(GetShardMapRequest.getDefaultInstance(), observer);

        assertNull(observer.error);
        assertTrue(observer.completed);
        assertEquals(ClusterMembershipService.CONTRACT_VERSION, observer.value.getContractVersion());
        assertEquals(membershipService.getTopologyEpoch(), observer.value.getTopologyEpoch());
        assertEquals(membershipService.getTopologyVersion(), observer.value.getTopologyVersion());
        assertEquals(
                List.of("index/a-node", "index/z-node"),
                observer.value.getShardLocationsList().stream()
                        .map(location -> location.getShardId())
                        .toList());
    }

    @Test
    void shardMapRejectsVersionNewerThanDurableCoordinatorState() {
        ClusterMembershipService membershipService = new ClusterMembershipService(appConfig());
        ClusterServiceImpl service = new ClusterServiceImpl(membershipService);
        CapturingObserver<GetShardMapResponse> observer = new CapturingObserver<>();

        service.getShardMap(
                GetShardMapRequest.newBuilder()
                        .setMinTopologyVersion(membershipService.getTopologyVersion() + 1)
                        .build(),
                observer);

        assertStatus(
                observer.error,
                Status.Code.FAILED_PRECONDITION,
                "Requested topology version " + (membershipService.getTopologyVersion() + 1) + " but coordinator has "
                        + membershipService.getTopologyVersion());
    }

    private static RegisterNodeResponse registerNode(ClusterServiceImpl service, RegisterNodeRequest request) {
        CapturingObserver<RegisterNodeResponse> observer = new CapturingObserver<>();

        service.registerNode(request, observer);

        assertNull(observer.error);
        assertTrue(observer.completed);
        assertNotNull(observer.value);
        return observer.value;
    }

    private static GetClusterInfoResponse getClusterInfo(ClusterServiceImpl service, NodeRole role) {
        CapturingObserver<GetClusterInfoResponse> observer = new CapturingObserver<>();

        service.getClusterInfo(GetClusterInfoRequest.newBuilder().setRole(role).build(), observer);

        assertNull(observer.error);
        assertTrue(observer.completed);
        assertNotNull(observer.value);
        return observer.value;
    }

    private static NodeInfo findNode(GetClusterInfoResponse response, String nodeId) {
        return response.getNodesList().stream()
                .filter(node -> node.getNodeId().equals(nodeId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected node missing from cluster info: " + nodeId));
    }

    private static void assertNode(NodeInfo node, String nodeId, String host, int port, int healthPort, NodeRole role) {
        assertEquals(nodeId, node.getNodeId());
        assertEquals(host, node.getHost());
        assertEquals(port, node.getPort());
        assertEquals(healthPort, node.getHealthPort());
        assertEquals(role, node.getRole());
    }

    private static void assertRegisterStatus(
            RegisterNodeRequest request, Status.Code expectedCode, String expectedDescription) {
        ClusterServiceImpl service = new ClusterServiceImpl(new ClusterMembershipService(appConfig()));
        CapturingObserver<RegisterNodeResponse> observer = new CapturingObserver<>();

        service.registerNode(request, observer);

        assertStatus(observer.error, expectedCode, expectedDescription);
    }

    private static void assertStatus(Throwable error, Status.Code expectedCode, String expectedDescription) {
        assertNotNull(error);
        Status status = Status.fromThrowable(error);
        assertEquals(expectedCode, status.getCode());
        assertEquals(expectedDescription, status.getDescription());
    }

    private static RegisterNodeRequest registerRequest(
            String nodeId, String host, int port, int healthPort, NodeRole role) {
        return RegisterNodeRequest.newBuilder()
                .setNodeId(nodeId)
                .setHost(host)
                .setPort(port)
                .setHealthPort(healthPort)
                .setRole(role)
                .build();
    }

    private static RegisterNodeRequest.Builder validRegisterRequest() {
        return RegisterNodeRequest.newBuilder()
                .setNodeId("node-a")
                .setHost("localhost")
                .setPort(5000)
                .setHealthPort(5100)
                .setRole(NodeRole.NODE_ROLE_INDEX);
    }

    private static AppConfig appConfig() {
        AppConfig config = new AppConfig();
        AppConfig.ServiceDiscoveryConfig discovery = new AppConfig.ServiceDiscoveryConfig();
        discovery.setNodeExpirySeconds(30);
        config.setServiceDiscovery(discovery);
        config.setIndexNodes(nodeGroupConfig("index-nodes", RoutingStrategy.ROUND_ROBIN));
        config.setQueryNodes(nodeGroupConfig("query-nodes", RoutingStrategy.LEAST_LOADED));
        config.setCoordinatorNodes(nodeGroupConfig("coordinator-nodes", RoutingStrategy.ROUND_ROBIN));
        return config;
    }

    private static AppConfig.NodeGroupConfig nodeGroupConfig(String componentLabel, RoutingStrategy routingStrategy) {
        AppConfig.NodeGroupConfig group = new AppConfig.NodeGroupConfig();
        group.setComponentLabel(componentLabel);
        group.setRoutingStrategy(routingStrategy);
        group.setReplicationFactor(1);
        group.setNodes(List.of());
        return group;
    }

    private static final class CapturingObserver<T> implements StreamObserver<T> {
        private T value;
        private Throwable error;
        private boolean completed;

        @Override
        public void onNext(T value) {
            this.value = value;
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
        }

        @Override
        public void onCompleted() {
            completed = true;
        }
    }
}
