package com.danieljhkim.dsearch.coordinator.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import com.danieljhkim.dsearch.proto.cluster.RegisterNodeRequest;
import com.danieljhkim.dsearch.proto.cluster.RegisterNodeResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClusterServiceImplTest {

    @Test
    void registerNodeRegistersValidNode() {
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

        assertRegisterStatus(request, Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void registerNodeRejectsEmptyNodeId() {
        RegisterNodeRequest request = validRegisterRequest().setNodeId("").build();

        assertRegisterStatus(request, Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void registerNodeRejectsInvalidHost() {
        RegisterNodeRequest request = validRegisterRequest().setHost("bad host").build();

        assertRegisterStatus(request, Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void registerNodeRejectsInvalidPort() {
        RegisterNodeRequest request = validRegisterRequest().setPort(0).build();

        assertRegisterStatus(request, Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void registerNodeRejectsInvalidHealthPort() {
        RegisterNodeRequest request =
                validRegisterRequest().setHealthPort(70000).build();

        assertRegisterStatus(request, Status.Code.INVALID_ARGUMENT);
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

        assertEquals(Status.Code.NOT_FOUND, Status.fromThrowable(observer.error).getCode());
    }

    @Test
    void getClusterInfoRejectsInvalidRole() {
        ClusterServiceImpl service = new ClusterServiceImpl(new ClusterMembershipService(appConfig()));
        CapturingObserver<GetClusterInfoResponse> observer = new CapturingObserver<>();
        GetClusterInfoRequest request = GetClusterInfoRequest.newBuilder()
                .setRole(NodeRole.NODE_ROLE_UNKNOWN)
                .build();

        service.getClusterInfo(request, observer);

        assertEquals(
                Status.Code.INVALID_ARGUMENT,
                Status.fromThrowable(observer.error).getCode());
    }

    @Test
    void heartbeatIsExplicitlyDeferred() {
        ClusterServiceImpl service = new ClusterServiceImpl(new ClusterMembershipService(appConfig()));
        CapturingObserver<HeartbeatResponse> observer = new CapturingObserver<>();

        service.heartbeat(HeartbeatRequest.newBuilder().setNodeId("node-a").build(), observer);

        assertEquals(
                Status.Code.UNIMPLEMENTED, Status.fromThrowable(observer.error).getCode());
    }

    @Test
    void shardMapIsExplicitlyDeferred() {
        ClusterServiceImpl service = new ClusterServiceImpl(new ClusterMembershipService(appConfig()));
        CapturingObserver<GetShardMapResponse> observer = new CapturingObserver<>();

        service.getShardMap(GetShardMapRequest.getDefaultInstance(), observer);

        assertEquals(
                Status.Code.UNIMPLEMENTED, Status.fromThrowable(observer.error).getCode());
    }

    private static void assertRegisterStatus(RegisterNodeRequest request, Status.Code expectedCode) {
        ClusterServiceImpl service = new ClusterServiceImpl(new ClusterMembershipService(appConfig()));
        CapturingObserver<RegisterNodeResponse> observer = new CapturingObserver<>();

        service.registerNode(request, observer);

        assertEquals(expectedCode, Status.fromThrowable(observer.error).getCode());
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
        config.setIndexNodes(nodeGroupConfig("index-0", 5000, 5100));
        config.setQueryNodes(nodeGroupConfig("query-0", 6000, 6100));
        config.setCoordinatorNodes(nodeGroupConfig("coordinator-0", 7000, 7100));
        return config;
    }

    private static AppConfig.NodeGroupConfig nodeGroupConfig(String id, int port, int healthPort) {
        AppConfig.NodeConfig node = new AppConfig.NodeConfig();
        node.setId(id);
        node.setHost("localhost");
        node.setPort(port);
        node.setHealthPort(healthPort);

        AppConfig.NodeGroupConfig group = new AppConfig.NodeGroupConfig();
        group.setComponentLabel("component-" + id);
        group.setRoutingStrategy(RoutingStrategy.ROUND_ROBIN);
        group.setReplicationFactor(1);
        group.setNodes(List.of(node));
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
