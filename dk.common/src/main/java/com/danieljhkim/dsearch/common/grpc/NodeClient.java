package com.danieljhkim.dsearch.common.grpc;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.danieljhkim.dsearch.common.shard.ShardState;

import io.grpc.ManagedChannel;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class NodeClient<T> {
	private final String nodeId;
	private final ManagedChannel channel;
	private final Map<String, ShardState> shardStates = new ConcurrentHashMap<>();
	private final String host;
	private final int healthPort;
	private T stub;
	private boolean isActive = true;

	public NodeClient(String nodeId, T stub, ManagedChannel channel, String host, int healthPort) {
		this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
		this.stub = Objects.requireNonNull(stub, "stub must not be null");
		this.channel = Objects.requireNonNull(channel, "channel must not be null");
		this.host = host;
		this.healthPort = healthPort;
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
