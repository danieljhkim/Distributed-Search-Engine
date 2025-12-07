package com.danieljhkim.dsearch.common.grpc;

import com.danieljhkim.dsearch.common.shard.ShardState;
import io.grpc.ManagedChannel;
import lombok.Getter;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class NodeClient<T> {
    private final String nodeId;
    private final T stub;
    private final ManagedChannel channel;
    private final Map<String, ShardState> shardStates = new ConcurrentHashMap<>();

    public NodeClient(String nodeId, T stub, ManagedChannel channel) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
        this.stub = Objects.requireNonNull(stub, "stub must not be null");
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
    }

    public long getShardDocCount(String shardId) {
        ShardState shardState = getOrCreateShardState(shardId);
        return shardState.getDocumentCount();
    }

    public void incrementDocToShard(String shardId) {
        ShardState shardState = getOrCreateShardState(shardId);
        shardState.incrementDocs();
    }

    public void decrementDocFromShard(String shardId) {
        ShardState shardState = getOrCreateShardState(shardId);
        shardState.decrementDocs();
    }

    public ShardState getOrCreateShardState(String shardId) {
        return shardStates.computeIfAbsent(shardId, k -> new ShardState(k, nodeId));
    }
}
