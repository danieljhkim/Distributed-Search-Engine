package com.danieljhkim.dsearch.common.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.danieljhkim.dsearch.common.cluster.NodeGroup;
import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.enums.RoutingStrategy;
import com.danieljhkim.dsearch.common.exception.NodeUnavailableException;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import io.grpc.ManagedChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class NodeClientManagerTest {

    @Test
    void staticConfigModeUsesInitialActiveClients() {
        NodeClientManager<String> manager = new NodeClientManager<>(
                clients("0", "1"), RoutingStrategy.ROUND_ROBIN, NodeRole.NODE_ROLE_INDEX, ch -> "");

        assertEquals(List.of("0", "1"), activeNodeIds(manager));
        assertEquals("stub-0", manager.nextClient());
        assertEquals("stub-1", manager.nextClient());
    }

    @Test
    void discoveryRefreshAddingNodeUpdatesRoundRobinSnapshot() {
        AtomicReference<NodeGroup> discovered = new AtomicReference<>(nodeGroup(RoutingStrategy.ROUND_ROBIN, "0"));
        NodeClientManager<String> manager = discoveryBackedManager(RoutingStrategy.ROUND_ROBIN, discovered, "0");

        discovered.set(nodeGroup(RoutingStrategy.ROUND_ROBIN, "0", "1"));
        manager.refreshClientsFromCluster();

        assertEquals(List.of("0", "1"), activeNodeIds(manager));
        assertEquals(Set.of("stub-0", "stub-1"), Set.of(manager.nextClient(), manager.nextClient()));
    }

    @Test
    void roundRobinRoutingNeverSelectsInitiallyInactiveClients() {
        Map<String, NodeClient<String>> clients = clients("0", "1");
        clients.get("0").setActive(false);
        NodeClientManager<String> manager =
                new NodeClientManager<>(clients, RoutingStrategy.ROUND_ROBIN, NodeRole.NODE_ROLE_INDEX, ch -> "");

        assertEquals(List.of("1"), activeNodeIds(manager));
        assertEquals("stub-1", manager.nextClient());
        assertEquals("stub-1", manager.nextClient());
    }

    @Test
    void ownerRoutingIgnoresShardLoad() {
        Map<String, NodeClient<String>> clients = clients("0", "1");
        NodeClientManager<String> manager =
                new NodeClientManager<>(clients, RoutingStrategy.LEAST_LOADED, NodeRole.NODE_ROLE_INDEX, ch -> "");
        String owner = manager.ownerNodeId("p0", "doc-1");

        assertEquals(owner, manager.ownerNodeId("p0", "doc-1"));
        assertSame(clients.get(owner), manager.ownerClient("p0", "doc-1"));
    }

    @Test
    void ownerRoutingIsStableAcrossRepeatedLookups() {
        Map<String, NodeClient<String>> clients = clients("0", "1");
        NodeClientManager<String> manager =
                new NodeClientManager<>(clients, RoutingStrategy.LEAST_LOADED, NodeRole.NODE_ROLE_INDEX, ch -> "");

        manager.ownerClient("p0", "doc-1");
        manager.ownerClient("p0", "doc-1");

        assertEquals(manager.ownerNodeId("p0", "doc-1"), manager.ownerNodeId("p0", "doc-1"));
    }

    @Test
    void ownerClientRejectsMutationsWhenTheOwnerIsInactive() {
        Map<String, NodeClient<String>> clients = clients("0", "1");
        NodeClientManager<String> manager =
                new NodeClientManager<>(clients, RoutingStrategy.LEAST_LOADED, NodeRole.NODE_ROLE_INDEX, ch -> "");
        String owner = manager.ownerNodeId("p0", "doc-1");
        clients.get(owner).setActive(false);

        NodeUnavailableException ex =
                assertThrows(NodeUnavailableException.class, () -> manager.ownerClient("p0", "doc-1"));

        assertEquals(owner, ex.getNodeId());
    }

    @Test
    void ownershipRingIsFixedAtConstructionSoDiscoveryChurnCannotMoveDocuments() {
        AtomicReference<NodeGroup> discovered =
                new AtomicReference<>(nodeGroup(RoutingStrategy.LEAST_LOADED, "0", "1"));
        NodeClientManager<String> manager = discoveryBackedManager(RoutingStrategy.LEAST_LOADED, discovered, "0", "1");
        String owner = manager.ownerNodeId("p0", "doc-1");
        String other = owner.equals("0") ? "1" : "0";

        // The owner drops out of discovery and a brand new node joins.
        discovered.set(nodeGroup(RoutingStrategy.LEAST_LOADED, other, "2"));
        manager.refreshClientsFromCluster();

        assertEquals(List.of("0", "1"), manager.getOwnershipNodeIds());
        assertEquals(owner, manager.ownerNodeId("p0", "doc-1"));
        assertThrows(NodeUnavailableException.class, () -> manager.ownerClient("p0", "doc-1"));

        // ... and ownership is restored, not reassigned, once the node is healthy again.
        discovered.set(nodeGroup(RoutingStrategy.LEAST_LOADED, "0", "1", "2"));
        manager.refreshClientsFromCluster();

        assertEquals(owner, manager.ownerClient("p0", "doc-1").getNodeId());
    }

    @Test
    void ownershipSurvivesRebuildingTheManagerAsAfterAGatewayRestart() {
        NodeClientManager<String> before = new NodeClientManager<>(
                clients("0", "1", "2"), RoutingStrategy.LEAST_LOADED, NodeRole.NODE_ROLE_INDEX, ch -> "");
        NodeClientManager<String> after = new NodeClientManager<>(
                clients("0", "1", "2"), RoutingStrategy.LEAST_LOADED, NodeRole.NODE_ROLE_INDEX, ch -> "");

        for (int i = 0; i < 100; i++) {
            String documentId = "doc-" + i;
            assertEquals(before.ownerNodeId("p0", documentId), after.ownerNodeId("p0", documentId));
        }
    }

    @Test
    void discoveryRefreshDeactivatingNodeRemovesItFromRoundRobinRouting() {
        AtomicReference<NodeGroup> discovered = new AtomicReference<>(nodeGroup(RoutingStrategy.ROUND_ROBIN, "0", "1"));
        NodeClientManager<String> manager = discoveryBackedManager(RoutingStrategy.ROUND_ROBIN, discovered, "0", "1");

        discovered.set(nodeGroup(RoutingStrategy.ROUND_ROBIN, "1"));
        manager.refreshClientsFromCluster();

        assertFalse(manager.getClientMap().get("0").isActive());
        assertTrue(manager.getClientMap().get("1").isActive());
        assertEquals(List.of("1"), activeNodeIds(manager));
        assertEquals("stub-1", manager.nextClient());
        assertEquals("stub-1", manager.nextClient());
    }

    @Test
    void discoveryRefreshDeactivatingNodeRemovesItFromReadRouting() {
        AtomicReference<NodeGroup> discovered =
                new AtomicReference<>(nodeGroup(RoutingStrategy.LEAST_LOADED, "0", "1"));
        NodeClientManager<String> manager = discoveryBackedManager(RoutingStrategy.LEAST_LOADED, discovered, "0", "1");
        discovered.set(nodeGroup(RoutingStrategy.LEAST_LOADED, "1"));
        manager.refreshClientsFromCluster();

        assertFalse(manager.getClientMap().get("0").isActive());
        assertTrue(manager.getClientMap().get("1").isActive());
        assertEquals(List.of("1"), activeNodeIds(manager));
        assertEquals("stub-1", manager.nextClient());
    }

    @Test
    void shutdownClosesOnlyOwnedChannels() {
        ManagedChannel ownedChannel0 = mockChannel();
        ManagedChannel ownedChannel1 = mockChannel();
        ManagedChannel coordinatorChannel = mockChannel();
        Map<String, NodeClient<String>> ownedClients = new HashMap<>();
        ownedClients.put("0", new NodeClient<>("0", "stub-0", ownedChannel0, "localhost", 9000));
        ownedClients.put("1", new NodeClient<>("1", "stub-1", ownedChannel1, "localhost", 9001));
        NodeClientManager<String> manager =
                new NodeClientManager<>(ownedClients, RoutingStrategy.ROUND_ROBIN, NodeRole.NODE_ROLE_INDEX, ch -> "");

        new NodeClientManager<>(
                Map.of("c0", new NodeClient<>("c0", "coordinator", coordinatorChannel, "localhost", 7000)),
                RoutingStrategy.ROUND_ROBIN,
                NodeRole.NODE_ROLE_COORDINATOR,
                ch -> "");
        manager.shutdown();

        verify(ownedChannel0).shutdown();
        verify(ownedChannel1).shutdown();
        verify(coordinatorChannel, never()).shutdown();
    }

    @Test
    void shutdownIsIdempotentForAlreadyClosedChannels() {
        ManagedChannel channel = mockChannelThatBecomesShutdown();
        NodeClientManager<String> manager = new NodeClientManager<>(
                Map.of("0", new NodeClient<>("0", "stub-0", channel, "localhost", 9000)),
                RoutingStrategy.ROUND_ROBIN,
                NodeRole.NODE_ROLE_INDEX,
                ch -> "");

        manager.shutdown();
        manager.shutdown();

        verify(channel, times(1)).shutdown();
    }

    private static NodeClientManager<String> discoveryBackedManager(
            RoutingStrategy routingStrategy, AtomicReference<NodeGroup> discovered, String... initialNodeIds) {
        return new NodeClientManager<>(
                clients(initialNodeIds),
                routingStrategy,
                NodeRole.NODE_ROLE_INDEX,
                role -> discovered.get(),
                null,
                node -> client(node.getNodeId()));
    }

    private static List<String> activeNodeIds(NodeClientManager<String> manager) {
        return manager.activeClientsSnapshot().stream()
                .map(NodeClient::getNodeId)
                .toList();
    }

    private static Map<String, NodeClient<String>> clients(String... nodeIds) {
        Map<String, NodeClient<String>> clients = new HashMap<>();
        for (String nodeId : nodeIds) {
            clients.put(nodeId, client(nodeId));
        }
        return clients;
    }

    private static NodeClient<String> client(String nodeId) {
        int portOffset = Integer.parseInt(nodeId.replaceAll("\\D", "").isEmpty() ? "0" : nodeId.replaceAll("\\D", ""));
        return new NodeClient<>(nodeId, "stub-" + nodeId, mockChannel(), "localhost", 9000 + portOffset);
    }

    private static NodeGroup nodeGroup(RoutingStrategy routingStrategy, String... nodeIds) {
        AppConfig.NodeGroupConfig config = new AppConfig.NodeGroupConfig();
        config.setRoutingStrategy(routingStrategy);
        config.setComponentLabel("test-component");
        config.setNodes(List.of(nodeIds).stream()
                .map(nodeId -> {
                    AppConfig.NodeConfig nodeConfig = new AppConfig.NodeConfig();
                    nodeConfig.setId(nodeId);
                    nodeConfig.setHost("localhost");
                    nodeConfig.setPort(8000 + Integer.parseInt(nodeId));
                    nodeConfig.setHealthPort(9000 + Integer.parseInt(nodeId));
                    return nodeConfig;
                })
                .toList());
        return NodeGroup.fromConfig(NodeRole.NODE_ROLE_INDEX, config);
    }

    private static ManagedChannel mockChannel() {
        ManagedChannel channel = mock(ManagedChannel.class);
        when(channel.isShutdown()).thenReturn(false);
        when(channel.isTerminated()).thenReturn(false);
        when(channel.shutdown()).thenReturn(channel);
        return channel;
    }

    private static ManagedChannel mockChannelThatBecomesShutdown() {
        ManagedChannel channel = mock(ManagedChannel.class);
        when(channel.isShutdown()).thenReturn(false, true);
        when(channel.isTerminated()).thenReturn(false);
        when(channel.shutdown()).thenReturn(channel);
        return channel;
    }
}
