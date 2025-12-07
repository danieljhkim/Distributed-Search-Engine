package com.danieljhkim.dsearch.common.shard;

import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.atomic.AtomicLong;

@Getter
@Setter
public class ShardState {
    private final ShardId shardId;
    private final String partitionId;
    private final String nodeId;
    private final AtomicLong docCount = new AtomicLong(0);
    private volatile boolean isActive;

    public ShardState(String partitionId, String nodeId) {
        this(partitionId, nodeId, 0L);
    }

    public ShardState(String partitionId, String nodeId, long initialCount) {
        this.partitionId = partitionId;
        this.nodeId = nodeId;
        this.docCount.set(initialCount);
        this.isActive = true;
        this.shardId = new ShardId(nodeId, partitionId);
    }

    public long getDocumentCount() {
        return docCount.get();
    }

    public long incrementDocs() {
        return docCount.incrementAndGet();
    }

    public long decrementDocs() {
        long cnt = docCount.get();
        if (cnt <= 0) {
            return 0;
        }
        return docCount.decrementAndGet();
    }
}
