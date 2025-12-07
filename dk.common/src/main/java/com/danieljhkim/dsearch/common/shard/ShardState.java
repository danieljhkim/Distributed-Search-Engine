package com.danieljhkim.dsearch.common.shard;

import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.atomic.AtomicLong;

@Getter
@Setter
public class ShardState {
    private final String shardId;
    private final String nodeId;
    private final AtomicLong docCount = new AtomicLong(0);
    private volatile boolean isActive;

    public ShardState(String shardId, String nodeId) {
        this(shardId, nodeId, 0L);
    }

    public ShardState(String shardId, String nodeId, long initialCount) {
        this.shardId = shardId;
        this.nodeId = nodeId;
        this.docCount.set(initialCount);
        this.isActive = true;
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
