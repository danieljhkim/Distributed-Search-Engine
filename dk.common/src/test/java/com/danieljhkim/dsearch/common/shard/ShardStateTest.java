package com.danieljhkim.dsearch.common.shard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShardStateTest {

    @Test
    void documentCountOperationsAreAtomicAndBoundedAtZero() {
        ShardState state = new ShardState("partition-a", "node-1", 4);
        assertEquals("partition-a_node-1", state.getShardId().getId());
        assertTrue(state.isActive());
        assertEquals(5, state.incrementDocs());
        assertEquals(4, state.decrementDocs());
        state.decrementDocs();
        state.decrementDocs();
        state.decrementDocs();
        state.decrementDocs();
        assertEquals(0, state.getDocumentCount());
        state.setActive(false);
        assertFalse(state.isActive());
    }
}
