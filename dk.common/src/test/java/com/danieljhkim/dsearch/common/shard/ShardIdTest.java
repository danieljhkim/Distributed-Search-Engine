package com.danieljhkim.dsearch.common.shard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class ShardIdTest {

    @Test
    void shardIdsUseStableValueEqualityAndFormatting() {
        ShardId first = new ShardId("node-1", "partition-a");
        ShardId second = new ShardId("partition-a_node-1");
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals("ShardId{id='partition-a_node-1'}", first.toString());
        assertNotEquals(first, new ShardId("other"));
        assertNotEquals(first, null);
    }
}
