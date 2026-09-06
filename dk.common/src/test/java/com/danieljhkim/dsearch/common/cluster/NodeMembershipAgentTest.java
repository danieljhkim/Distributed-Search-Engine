package com.danieljhkim.dsearch.common.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.danieljhkim.dsearch.common.cluster.NodeMembershipAgent.NodeIdentity;
import com.danieljhkim.dsearch.common.cluster.NodeMembershipAgent.ResolvedMembership;
import com.danieljhkim.dsearch.common.cluster.NodeMembershipAgent.Settings;
import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.enums.RoutingStrategy;
import com.danieljhkim.dsearch.proto.cluster.ClusterServiceGrpc;
import com.danieljhkim.dsearch.proto.cluster.DeregisterNodeRequest;
import com.danieljhkim.dsearch.proto.cluster.DeregisterNodeResponse;
import com.danieljhkim.dsearch.proto.cluster.HeartbeatRequest;
import com.danieljhkim.dsearch.proto.cluster.HeartbeatResponse;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import com.danieljhkim.dsearch.proto.cluster.RegisterNodeRequest;
import com.danieljhkim.dsearch.proto.cluster.RegisterNodeResponse;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NodeMembershipAgentTest {

    @Test
    void resolvesStableIdentityAndLeaseSafeSettingsFromEnvironmentAndConfig() {
        AppConfig config = config(true, 6);

        ResolvedMembership resolved = NodeMembershipAgent.resolve(
                config,
                Map.of(
                        "INDEX_NODE_ID", "index-configured",
                        "INDEX_NODE_HOST", "advertised.local",
                        "COORDINATOR_HOST", "coordinator.override",
                        "COORDINATOR_PORT", "7010"),
                NodeRole.NODE_ROLE_INDEX,
                5000,
                5100);

        assertEquals("index-configured", resolved.identity().nodeId());
        assertEquals("advertised.local", resolved.identity().host());
        assertEquals(5000, resolved.identity().port());
        assertEquals("coordinator.override", resolved.settings().coordinatorHost());
        assertEquals(7010, resolved.settings().coordinatorPort());
        assertEquals(Duration.ofSeconds(2), resolved.settings().heartbeatInterval());

        assertNull(NodeMembershipAgent.resolve(config(false, 30), Map.of(), NodeRole.NODE_ROLE_INDEX, 5000, 5100));
    }

    @Test
    void rejectsMissingIdentityCoordinatorAndUnsupportedRole() {
        AppConfig config = config(true, 30);
        config.getIndexNodes().setNodes(List.of());
        config.getCoordinatorNodes().setNodes(List.of());

        assertThrows(
                IllegalArgumentException.class,
                () -> NodeMembershipAgent.resolve(config, Map.of(), NodeRole.NODE_ROLE_INDEX, 5000, 5100));
        assertThrows(
                IllegalArgumentException.class,
                () -> NodeMembershipAgent.resolve(config, Map.of(), NodeRole.NODE_ROLE_COORDINATOR, 7000, 7100));
    }

    @Test
    void retriesUnavailableRegistrationWithBoundedBackoffThenHeartbeats() throws Exception {
        Fixture fixture = fixture();
        when(fixture.client.registerNode(any(RegisterNodeRequest.class)))
                .thenThrow(Status.UNAVAILABLE.asRuntimeException())
                .thenThrow(Status.UNAVAILABLE.asRuntimeException())
                .thenReturn(registration("epoch-a", 2));
        when(fixture.client.heartbeat(any(HeartbeatRequest.class))).thenReturn(heartbeat("epoch-a", 2));

        fixture.agent.start();
        await(fixture.agent::isRegistered);

        verify(fixture.client, times(3)).registerNode(any(RegisterNodeRequest.class));
        await(() -> invocationCount(fixture.client) >= 1);
        fixture.close();
    }

    @Test
    void missingHeartbeatMembershipAutomaticallyReregistersSameNodeIdentity() throws Exception {
        Fixture fixture = fixture();
        when(fixture.client.registerNode(any(RegisterNodeRequest.class)))
                .thenReturn(registration("epoch-a", 2), registration("epoch-a", 4));
        when(fixture.client.heartbeat(any(HeartbeatRequest.class)))
                .thenThrow(Status.NOT_FOUND.asRuntimeException())
                .thenReturn(heartbeat("epoch-a", 4));

        fixture.agent.start();
        await(() -> fixture.agent.isRegistered() && fixture.agent.observedVersion() == 4);

        ArgumentCaptor<RegisterNodeRequest> requests = ArgumentCaptor.forClass(RegisterNodeRequest.class);
        verify(fixture.client, times(2)).registerNode(requests.capture());
        assertEquals(
                List.of("node-1", "node-1"),
                requests.getAllValues().stream()
                        .map(RegisterNodeRequest::getNodeId)
                        .toList());
        fixture.close();
    }

    @Test
    void changedEpochForcesRegistrationBeforeAcceptingLowerVersion() throws Exception {
        Fixture fixture = fixture();
        when(fixture.client.registerNode(any(RegisterNodeRequest.class)))
                .thenReturn(registration("epoch-a", 9), registration("epoch-b", 2));
        when(fixture.client.heartbeat(any(HeartbeatRequest.class)))
                .thenReturn(heartbeat("epoch-b", 1), heartbeat("epoch-b", 2));

        fixture.agent.start();
        await(() -> fixture.agent.isRegistered()
                && fixture.agent.observedEpoch().equals("epoch-b")
                && fixture.agent.observedVersion() == 2);

        verify(fixture.client, times(2)).registerNode(any(RegisterNodeRequest.class));
        fixture.close();
    }

    @Test
    void closeAttemptsBoundedDeregistrationButNeverPropagatesFailure() throws Exception {
        Fixture fixture = fixture();
        when(fixture.client.registerNode(any(RegisterNodeRequest.class))).thenReturn(registration("epoch-a", 2));
        when(fixture.client.heartbeat(any(HeartbeatRequest.class))).thenReturn(heartbeat("epoch-a", 2));
        when(fixture.client.deregisterNode(any(DeregisterNodeRequest.class)))
                .thenThrow(Status.UNAVAILABLE.asRuntimeException());

        fixture.agent.start();
        await(fixture.agent::isRegistered);
        fixture.agent.close();

        verify(fixture.client).deregisterNode(any(DeregisterNodeRequest.class));
        verify(fixture.channel).shutdownNow();
        assertTrue(fixture.executor.isShutdown());
    }

    @Test
    void retryDelayUsesJitterAndNeverExceedsConfiguredMaximum() {
        Fixture fixture = fixture(bound -> bound / 2);

        assertEquals(1, fixture.agent.retryDelayMillis(1));
        assertEquals(2, fixture.agent.retryDelayMillis(2));
        assertEquals(4, fixture.agent.retryDelayMillis(20));
        assertFalse(fixture.agent.isRegistered());
        fixture.close();
    }

    private static Fixture fixture() {
        return fixture(bound -> bound);
    }

    private static Fixture fixture(java.util.function.LongUnaryOperator jitter) {
        ClusterServiceGrpc.ClusterServiceBlockingStub client =
                mock(ClusterServiceGrpc.ClusterServiceBlockingStub.class);
        ManagedChannel channel = mock(ManagedChannel.class);
        var executor = Executors.newSingleThreadScheduledExecutor();
        when(client.withDeadlineAfter(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(client);
        when(client.deregisterNode(any(DeregisterNodeRequest.class)))
                .thenReturn(DeregisterNodeResponse.newBuilder()
                        .setSuccess(true)
                        .setContractVersion(2)
                        .setTopologyEpoch("epoch-a")
                        .setTopologyVersion(3)
                        .build());
        Settings settings = new Settings(
                "localhost",
                7000,
                Duration.ofMillis(5),
                Duration.ofMillis(2),
                Duration.ofMillis(8),
                Duration.ofMillis(100),
                Duration.ofMillis(50));
        NodeMembershipAgent agent = new NodeMembershipAgent(
                new NodeIdentity("node-1", "localhost", 5000, 5100, NodeRole.NODE_ROLE_INDEX),
                settings,
                client,
                channel,
                executor,
                jitter);
        return new Fixture(agent, client, channel, executor);
    }

    private static RegisterNodeResponse registration(String epoch, long version) {
        return RegisterNodeResponse.newBuilder()
                .setSuccess(true)
                .setContractVersion(2)
                .setTopologyEpoch(epoch)
                .setTopologyVersion(version)
                .setLeaseDurationMillis(30)
                .build();
    }

    private static HeartbeatResponse heartbeat(String epoch, long version) {
        return HeartbeatResponse.newBuilder()
                .setSuccess(true)
                .setContractVersion(1)
                .setTopologyEpoch(epoch)
                .setTopologyVersion(version)
                .setLeaseDurationMillis(30)
                .build();
    }

    private static int invocationCount(ClusterServiceGrpc.ClusterServiceBlockingStub client) {
        return org.mockito.Mockito.mockingDetails(client).getInvocations().stream()
                .map(invocation -> invocation.getMethod().getName())
                .filter("heartbeat"::equals)
                .toList()
                .size();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(2);
        }
        throw new AssertionError("condition was not satisfied before timeout");
    }

    private static AppConfig config(boolean enabled, int expirySeconds) {
        AppConfig config = new AppConfig();
        AppConfig.ServiceDiscoveryConfig discovery = new AppConfig.ServiceDiscoveryConfig();
        discovery.setEnabled(enabled);
        discovery.setNodeExpirySeconds(expirySeconds);
        discovery.setHeartbeatIntervalSeconds(10);
        discovery.setRegistrationInitialBackoffMillis(250);
        discovery.setRegistrationMaxBackoffSeconds(10);
        discovery.setMembershipRpcDeadlineMillis(2000);
        discovery.setShutdownDeregisterTimeoutMillis(1000);
        config.setServiceDiscovery(discovery);
        config.setIndexNodes(group("index-configured", "index.local", 5000));
        config.setQueryNodes(group("query-configured", "query.local", 6000));
        config.setCoordinatorNodes(group("coordinator", "coordinator.local", 7000));
        return config;
    }

    private static AppConfig.NodeGroupConfig group(String id, String host, int port) {
        AppConfig.NodeConfig node = new AppConfig.NodeConfig();
        node.setId(id);
        node.setHost(host);
        node.setPort(port);
        node.setHealthPort(port + 100);
        AppConfig.NodeGroupConfig group = new AppConfig.NodeGroupConfig();
        group.setNodes(List.of(node));
        group.setRoutingStrategy(RoutingStrategy.ROUND_ROBIN);
        return group;
    }

    private record Fixture(
            NodeMembershipAgent agent,
            ClusterServiceGrpc.ClusterServiceBlockingStub client,
            ManagedChannel channel,
            java.util.concurrent.ScheduledExecutorService executor) {
        private void close() {
            agent.close();
        }
    }
}
