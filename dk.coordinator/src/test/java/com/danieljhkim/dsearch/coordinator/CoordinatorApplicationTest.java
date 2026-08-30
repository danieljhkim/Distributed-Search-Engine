package com.danieljhkim.dsearch.coordinator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.enums.RoutingStrategy;
import com.danieljhkim.dsearch.proto.cluster.ClusterServiceGrpc;
import com.danieljhkim.dsearch.proto.cluster.GetShardMapRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CoordinatorApplicationTest {

    @Test
    void testApplicationClassExists() {
        assertNotNull(CoordinatorApplication.class);
    }

    @Test
    void testMainMethodExists() {
        // Verify main method exists and is accessible
        assertDoesNotThrow(() -> {
            java.lang.reflect.Method mainMethod = CoordinatorApplication.class.getMethod("main", String[].class);
            assertNotNull(mainMethod);
        });
    }

    @Test
    void startsComposableGrpcAndHealthServicesOnEphemeralPortsAndCleansThemUp() throws Exception {
        CoordinatorApplication.CoordinatorRuntime runtime = CoordinatorApplication.start(config(), 0, 0);
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", runtime.grpcPort())
                .usePlaintext()
                .build();
        int grpcPort = runtime.grpcPort();
        int healthPort = runtime.healthPort();
        try {
            assertEquals(
                    0,
                    ClusterServiceGrpc.newBlockingStub(channel)
                            .getShardMap(GetShardMapRequest.getDefaultInstance())
                            .getShardLocationsCount());
            HttpURLConnection health = (HttpURLConnection) URI.create("http://localhost:" + healthPort + "/health")
                    .toURL()
                    .openConnection();
            assertEquals(200, health.getResponseCode());
            HttpURLConnection readiness = (HttpURLConnection) URI.create("http://localhost:" + healthPort + "/readyz")
                    .toURL()
                    .openConnection();
            assertEquals(200, readiness.getResponseCode());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            runtime.shutdown();
        }

        try (ServerSocket grpcRebound = new ServerSocket(grpcPort);
                ServerSocket healthRebound = new ServerSocket(healthPort)) {
            assertEquals(grpcPort, grpcRebound.getLocalPort());
            assertEquals(healthPort, healthRebound.getLocalPort());
        }
    }

    @Test
    void requiredPortsRejectMissingMalformedAndOutOfRangeValuesWithoutStartingServices() {
        assertThrows(IllegalArgumentException.class, () -> CoordinatorApplication.requiredPort("PORT", null));
        assertThrows(IllegalArgumentException.class, () -> CoordinatorApplication.requiredPort("PORT", "abc"));
        assertThrows(IllegalArgumentException.class, () -> CoordinatorApplication.requiredPort("PORT", "65536"));
        assertEquals(0, CoordinatorApplication.requiredPort("PORT", "0"));
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
