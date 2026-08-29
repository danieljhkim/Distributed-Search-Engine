package com.danieljhkim.dsearch.coordinator.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.enums.RoutingStrategy;
import com.danieljhkim.dsearch.coordinator.cluster.ClusterMembershipService;
import com.danieljhkim.dsearch.proto.cluster.ClusterServiceGrpc;
import com.danieljhkim.dsearch.proto.cluster.GetShardMapRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CoordinatorServerTest {

    @Test
    void startsGrpcServiceOnEphemeralPortAndReleasesItOnShutdown() throws Exception {
        CoordinatorServer server = new CoordinatorServer(0, new ClusterMembershipService(config()));
        ManagedChannel channel = null;
        int port = -1;
        try {
            server.startAsync();
            port = server.getPort();
            channel = ManagedChannelBuilder.forAddress("localhost", port)
                    .usePlaintext()
                    .build();

            assertEquals(
                    0,
                    ClusterServiceGrpc.newBlockingStub(channel)
                            .getShardMap(GetShardMapRequest.getDefaultInstance())
                            .getShardLocationsCount());
        } finally {
            if (channel != null) {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
            server.shutdown();
        }

        try (ServerSocket rebound = new ServerSocket(port)) {
            assertEquals(port, rebound.getLocalPort());
        }
    }

    @Test
    void startupFailureDoesNotPreventLaterServerFromUsingThePort() throws Exception {
        int port;
        try (ServerSocket occupied = new ServerSocket(0)) {
            port = occupied.getLocalPort();
            CoordinatorServer failed = new CoordinatorServer(port, new ClusterMembershipService(config()));
            assertThrows(IOException.class, failed::startAsync);
            failed.shutdown();
        }

        CoordinatorServer recovered = new CoordinatorServer(port, new ClusterMembershipService(config()));
        try {
            recovered.startAsync();
            assertEquals(port, recovered.getPort());
        } finally {
            recovered.shutdown();
        }
        assertTrue(port > 0);
    }

    private static AppConfig config() {
        AppConfig config = new AppConfig();
        AppConfig.ServiceDiscoveryConfig discovery = new AppConfig.ServiceDiscoveryConfig();
        discovery.setEnabled(false);
        config.setServiceDiscovery(discovery);
        config.setIndexNodes(group("index"));
        config.setQueryNodes(group("query"));
        config.setCoordinatorNodes(group("coordinator"));
        return config;
    }

    private static AppConfig.NodeGroupConfig group(String label) {
        AppConfig.NodeGroupConfig group = new AppConfig.NodeGroupConfig();
        group.setComponentLabel(label);
        group.setRoutingStrategy(RoutingStrategy.ROUND_ROBIN);
        group.setNodes(List.of());
        return group;
    }
}
