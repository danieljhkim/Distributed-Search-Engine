package com.danieljhkim.dsearch.coordinator.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.enums.RoutingStrategy;
import com.danieljhkim.dsearch.coordinator.cluster.ClusterMembershipService;
import com.danieljhkim.dsearch.proto.cluster.ClusterServiceGrpc;
import com.danieljhkim.dsearch.proto.cluster.GetShardMapRequest;
import com.danieljhkim.dsearch.proto.cluster.GetShardMapResponse;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import com.danieljhkim.dsearch.proto.cluster.RegisterNodeRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoordinatorTopologyIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void transportContractSurvivesRestartExpiresAndReregistersNodesAndRejectsStaleState() throws Exception {
        Path stateFile = tempDir.resolve("coordinator.properties");
        MutableClock clock = new MutableClock(Instant.parse("2026-08-29T00:00:00Z"));
        AppConfig config = appConfig();

        ClusterMembershipService firstMembership = new ClusterMembershipService(config, stateFile, clock);
        RunningCoordinator first = start(firstMembership);
        ClusterServiceGrpc.ClusterServiceBlockingStub firstClient = first.stub();
        firstClient.registerNode(registerRequest());
        GetShardMapResponse beforeRestart = firstClient.getShardMap(GetShardMapRequest.getDefaultInstance());
        assertEquals(
                List.of("index/index-0"),
                beforeRestart.getShardLocationsList().stream()
                        .map(location -> location.getShardId())
                        .toList());
        first.close();

        ClusterMembershipService recoveredMembership = new ClusterMembershipService(config, stateFile, clock);
        RunningCoordinator recovered = start(recoveredMembership);
        ClusterServiceGrpc.ClusterServiceBlockingStub recoveredClient = recovered.stub();
        GetShardMapResponse afterRestart = recoveredClient.getShardMap(GetShardMapRequest.getDefaultInstance());
        assertEquals(beforeRestart.getTopologyEpoch(), afterRestart.getTopologyEpoch());
        assertEquals(beforeRestart.getTopologyVersion(), afterRestart.getTopologyVersion());
        assertEquals(beforeRestart.getShardLocationsList(), afterRestart.getShardLocationsList());

        clock.advanceSeconds(6);
        assertEquals(List.of("NODE_ROLE_INDEX/index-0"), recoveredMembership.expireNodes());
        GetShardMapResponse afterExpiry = recoveredClient.getShardMap(GetShardMapRequest.getDefaultInstance());
        assertEquals(0, afterExpiry.getShardLocationsCount());
        assertTrue(afterExpiry.getTopologyVersion() > afterRestart.getTopologyVersion());

        long expiredVersion = afterExpiry.getTopologyVersion();
        long reregisteredVersion =
                recoveredClient.registerNode(registerRequest()).getTopologyVersion();
        assertTrue(reregisteredVersion > expiredVersion);

        StatusRuntimeException stale = assertThrows(
                StatusRuntimeException.class,
                () -> recoveredClient.getShardMap(GetShardMapRequest.newBuilder()
                        .setMinTopologyVersion(reregisteredVersion + 1)
                        .build()));
        assertEquals(Status.Code.FAILED_PRECONDITION, stale.getStatus().getCode());

        ClusterServiceGrpc.ClusterServiceBlockingStub unavailableClient = recovered.stub();
        recovered.stopServer();
        StatusRuntimeException unavailable = assertThrows(StatusRuntimeException.class, () -> unavailableClient
                .withDeadlineAfter(250, TimeUnit.MILLISECONDS)
                .getShardMap(GetShardMapRequest.getDefaultInstance()));
        assertEquals(Status.Code.UNAVAILABLE, unavailable.getStatus().getCode());
        recovered.closeChannel();
    }

    private static RunningCoordinator start(ClusterMembershipService membershipService) throws IOException {
        Server server = ServerBuilder.forPort(0)
                .addService(new ClusterServiceImpl(membershipService))
                .build()
                .start();
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", server.getPort())
                .usePlaintext()
                .build();
        return new RunningCoordinator(server, channel);
    }

    private static RegisterNodeRequest registerRequest() {
        return RegisterNodeRequest.newBuilder()
                .setNodeId("index-0")
                .setHost("localhost")
                .setPort(5000)
                .setHealthPort(5100)
                .setRole(NodeRole.NODE_ROLE_INDEX)
                .build();
    }

    private static AppConfig appConfig() {
        AppConfig config = new AppConfig();
        AppConfig.ServiceDiscoveryConfig discovery = new AppConfig.ServiceDiscoveryConfig();
        discovery.setEnabled(true);
        discovery.setNodeExpirySeconds(5);
        discovery.setMaxStalenessSeconds(5);
        config.setServiceDiscovery(discovery);
        config.setIndexNodes(nodeGroup("index-nodes", RoutingStrategy.ROUND_ROBIN));
        config.setQueryNodes(nodeGroup("query-nodes", RoutingStrategy.ROUND_ROBIN));
        config.setCoordinatorNodes(nodeGroup("coordinator-nodes", RoutingStrategy.ROUND_ROBIN));
        return config;
    }

    private static AppConfig.NodeGroupConfig nodeGroup(String label, RoutingStrategy strategy) {
        AppConfig.NodeGroupConfig group = new AppConfig.NodeGroupConfig();
        group.setComponentLabel(label);
        group.setRoutingStrategy(strategy);
        group.setNodes(List.of());
        return group;
    }

    private record RunningCoordinator(Server server, ManagedChannel channel) implements AutoCloseable {
        ClusterServiceGrpc.ClusterServiceBlockingStub stub() {
            return ClusterServiceGrpc.newBlockingStub(channel);
        }

        void stopServer() throws InterruptedException {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }

        void closeChannel() throws InterruptedException {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws InterruptedException {
            stopServer();
            closeChannel();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

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
