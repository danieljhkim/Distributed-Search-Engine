package com.danieljhkim.dsearch.common.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import io.grpc.ManagedChannel;
import org.junit.jupiter.api.Test;

class NodeClientTest {

    @Test
    void shardCountsAreCreatedLazilyAndNeverDropBelowZero() {
        NodeClient<String> client = new NodeClient<>("node-a", "stub", mock(ManagedChannel.class), "host", 5100);

        assertEquals(0, client.getShardDocCount("partition-a"));
        client.incrementDocToShard("partition-a");
        client.incrementDocToShard("partition-a");
        client.decrementDocFromShard("partition-a");
        client.decrementDocFromShard("partition-a");
        client.decrementDocFromShard("partition-a");

        assertEquals(0, client.getShardDocCount("partition-a"));
        assertSame(client.getOrCreateShardState("partition-a"), client.getOrCreateShardState("partition-a"));
        assertEquals("host", client.getHost());
        assertEquals(5100, client.getHealthPort());
    }

    @Test
    void constructorRejectsMissingRequiredValues() {
        ManagedChannel channel = mock(ManagedChannel.class);
        assertThrows(NullPointerException.class, () -> new NodeClient<>(null, "stub", channel, "host", 1));
        assertThrows(NullPointerException.class, () -> new NodeClient<>("node", null, channel, "host", 1));
        assertThrows(NullPointerException.class, () -> new NodeClient<>("node", "stub", null, "host", 1));
    }
}
