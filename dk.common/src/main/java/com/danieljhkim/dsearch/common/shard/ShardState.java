package com.danieljhkim.dsearch.common.shard;

import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.atomic.AtomicLong;

@Getter
@Setter
public class ShardState {
    private final String shardId;
    private final AtomicLong docCount = new AtomicLong(0);
    private volatile boolean isActive;

    public ShardState(String shardId) {
        this.shardId = shardId;
        this.isActive = true;
    }

    public long getDocCount() {
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
