package com.danieljhkim.dsearch.common.loadbalancer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.danieljhkim.dsearch.common.grpc.NodeClient;
import io.grpc.ManagedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RoundRobinTest {

    @Test
    void constructorRejectsEmptyStaticClientList() {
        assertThrows(IllegalArgumentException.class, () -> new RoundRobin<>(List.of()));
    }

    @Test
    void constructorRejectsNullStaticClientList() {
        assertThrows(IllegalArgumentException.class, () -> new RoundRobin<>(null));
    }

    @Test
    void nextReturnsStableSequenceAndWrapsAround() {
        RoundRobin<String> roundRobin = new RoundRobin<>(clients("node-0", "node-1", "node-2"));

        List<String> selectedNodeIds = IntStream.range(0, 5)
                .mapToObj(i -> roundRobin.next().getNodeId())
                .toList();

        assertIterableEquals(List.of("node-0", "node-1", "node-2", "node-0", "node-1"), selectedNodeIds);
    }

    @Test
    void dynamicRoundRobinRejectsEmptySupplierResults() {
        RoundRobin<String> roundRobin = RoundRobin.dynamic(List::of);

        assertThrows(IllegalStateException.class, roundRobin::next);
    }

    @Test
    void dynamicRoundRobinRejectsAllInactiveClients() {
        List<NodeClient<String>> clients = clients("node-0", "node-1");
        clients.forEach(client -> client.setActive(false));
        RoundRobin<String> roundRobin = RoundRobin.dynamic(() -> clients);

        assertThrows(IllegalStateException.class, roundRobin::next);
    }

    @Test
    void dynamicRoundRobinSkipsInactiveAndRemovedClients() {
        List<NodeClient<String>> clients = new ArrayList<>(clients("node-0", "node-1", "node-2"));
        RoundRobin<String> roundRobin = RoundRobin.dynamic(() -> clients);

        assertEquals("node-0", roundRobin.next().getNodeId());

        clients.get(1).setActive(false);
        assertEquals("node-2", roundRobin.next().getNodeId());

        clients.removeIf(client -> client.getNodeId().equals("node-2"));
        assertEquals("node-0", roundRobin.next().getNodeId());
        assertEquals("node-0", roundRobin.next().getNodeId());
    }

    @Test
    void dynamicRoundRobinSelectsNewlyAddedClients() {
        List<NodeClient<String>> clients = new ArrayList<>(clients("node-0"));
        RoundRobin<String> roundRobin = RoundRobin.dynamic(() -> clients);

        assertEquals("node-0", roundRobin.next().getNodeId());

        clients.add(client("node-1"));

        assertEquals("node-1", roundRobin.next().getNodeId());
        assertEquals("node-0", roundRobin.next().getNodeId());
    }

    private static List<NodeClient<String>> clients(String... nodeIds) {
        return IntStream.range(0, nodeIds.length)
                .mapToObj(i -> client(nodeIds[i]))
                .toList();
    }

    private static NodeClient<String> client(String nodeId) {
        ManagedChannel channel = mock(ManagedChannel.class);
        return new NodeClient<>(nodeId, "stub-" + nodeId, channel, "localhost", 8080);
    }
}
