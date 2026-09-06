package com.danieljhkim.dsearch.coordinator.scheduler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.common.cluster.NodeGroup;
import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.enums.RoutingStrategy;
import com.danieljhkim.dsearch.coordinator.cluster.ClusterMembershipService;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.junit.jupiter.api.Test;

class HealthCheckSchedulerTest {

    @Test
    void probesAffectHealthButDoNotRenewNodeOwnedMembershipLeases() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-29T00:00:00Z"));
        ClusterMembershipService membership = new ClusterMembershipService(config(true), null, clock);
        HttpServer healthy = healthServer(200);
        HttpServer unhealthy = healthServer(503);
        int unavailablePort = unusedPort();
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        HealthCheckScheduler scheduler =
                new HealthCheckScheduler(membership, config(true), HttpClient.newHttpClient(), executor);
        try {
            membership.registerNode(node("index-live", healthy.getAddress().getPort()), NodeRole.NODE_ROLE_INDEX);
            membership.registerNode(
                    node("index-write-fenced", unhealthy.getAddress().getPort()), NodeRole.NODE_ROLE_INDEX);
            membership.registerNode(node("index-unreachable", unavailablePort), NodeRole.NODE_ROLE_INDEX);

            assertDoesNotThrow(scheduler::checkClusterHealth);
            assertTrue(membership.getIndexGroup().getNode("index-live").isHealthy());
            assertFalse(membership.getIndexGroup().getNode("index-write-fenced").isHealthy());
            assertFalse(membership.getIndexGroup().getNode("index-unreachable").isHealthy());
            assertEquals(
                    List.of("index-live"),
                    membership.healthyNodes(NodeRole.NODE_ROLE_INDEX).stream()
                            .map(NodeGroup.NodeInfo::getNodeId)
                            .toList());

            clock.advanceSeconds(6);
            assertDoesNotThrow(scheduler::checkClusterHealth);
            assertFalse(membership.getIndexGroup().getAllNodes().stream()
                    .anyMatch(node -> node.getNodeId().equals("index-live")));
            assertFalse(membership.getIndexGroup().getAllNodes().stream()
                    .anyMatch(node -> node.getNodeId().equals("index-write-fenced")));
            assertFalse(membership.getIndexGroup().getAllNodes().stream()
                    .anyMatch(node -> node.getNodeId().equals("index-unreachable")));
        } finally {
            scheduler.shutdown();
            healthy.stop(0);
            unhealthy.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void startsOnlyWhenDiscoveryIsEnabledAndStopsScheduledWork() throws Exception {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        HealthCheckScheduler scheduler =
                new HealthCheckScheduler(new ClusterMembershipService(config(false)), config(false), null, executor);

        scheduler.start();

        assertTrue(executor.getQueue().isEmpty());
        scheduler.shutdown();
        assertTrue(executor.isShutdown());
    }

    @Test
    void enabledSchedulerRegistersOnePeriodicTaskAndCanBeStoppedBeforeItRuns() throws Exception {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        HealthCheckScheduler scheduler = new HealthCheckScheduler(
                new ClusterMembershipService(config(true)), config(true), HttpClient.newHttpClient(), executor);

        scheduler.start();

        assertFalse(executor.getQueue().isEmpty());
        scheduler.shutdown();
        assertTrue(executor.isShutdown());
    }

    private static HttpServer healthServer(int status) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/readyz", exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static NodeGroup.NodeInfo node(String nodeId, int healthPort) {
        return new NodeGroup.NodeInfo(nodeId, "localhost", healthPort - 1, healthPort, "INDEX", true);
    }

    private static AppConfig config(boolean enabled) {
        AppConfig config = new AppConfig();
        AppConfig.ServiceDiscoveryConfig discovery = new AppConfig.ServiceDiscoveryConfig();
        discovery.setEnabled(enabled);
        discovery.setRefreshIntervalSeconds(60);
        discovery.setNodeExpirySeconds(5);
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

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceSeconds(long seconds) {
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
