package com.danieljhkim.dsearch.common.grpc;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.enums.RoutingStrategy;
import com.danieljhkim.dsearch.common.loadbalancer.RoundRobin;
import com.danieljhkim.dsearch.common.shard.ShardState;
import com.danieljhkim.dsearch.common.shard.ShardStateStore;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.Getter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class NodeClientManager<T> {

    private static final Logger LOGGER = Logger.getLogger(NodeClientManager.class.getName());

    private final RoundRobin<T> rr;
    @Getter
    private final Map<String, NodeClient<T>> clientMap;
    private final List<ManagedChannel> channels;
    private final RoutingStrategy routingStrategy;

    public NodeClientManager(Map<String, NodeClient<T>> clientMap, RoutingStrategy routingStrategy) {
        this.channels = clientMap.values().stream()
                .map(NodeClient::getChannel)
                .toList();
        List<T> stubs = clientMap.values().stream()
                .map(NodeClient::getStub)
                .toList();
        this.rr = new RoundRobin<>(stubs);
        this.clientMap = Objects.requireNonNull(clientMap, "clientMap must not be null");
        this.routingStrategy = routingStrategy;
    }

    public static <T> NodeClientManager<T> fromConfig(
            AppConfig.NodeGroupConfig appConfig,
            Function<ManagedChannel, T> clientFactory
    ) {
        Map<String, NodeClient<T>> clientMap =
                appConfig.getNodes().stream()
                        .collect(Collectors.toMap(
                                node -> String.valueOf(node.getId()),
                                node -> {
                                    ManagedChannel channel = ManagedChannelBuilder
                                            .forAddress(node.getHost(), node.getPort())
                                            .usePlaintext()
                                            .build();
                                    T stub = clientFactory.apply(channel);
                                    return new NodeClient<>(
                                            String.valueOf(node.getId()),
                                            stub,
                                            channel
                                    );
                                }
                        ));
        return new NodeClientManager<>(clientMap, appConfig.getRoutingStrategy());
    }

    /**
     * Get a client/stub for the next node in round-robin order or least loaded for WRITE/DEL ops.
     */
    public T nextClient(String shardId, boolean isWriteOperation) {
        LOGGER.info("Routing strategy: " + this.routingStrategy);
        if (this.routingStrategy == RoutingStrategy.LEAST_LOADED) {
            return nextLeastLoadedClient(shardId, isWriteOperation);
        }
        return this.rr.next();
    }

    /**
     * Get a client/stub for the next node in round-robin for READ operations.
     */
    public T nextClient() {
        return this.rr.next();
    }

    public T nextLeastLoadedClient(String shardId, boolean isWriteOperation) { // TODO: handle update
        NodeClient<T> leastLoadedClient = null;
        long minDocCount = Long.MAX_VALUE;
        for (NodeClient<T> client : clientMap.values()) {
            long shardDocCount = client.getShardDocCount(shardId);
            LOGGER.info("Client " + client.getNodeId() + " has shard " + shardId + " doc count: " + shardDocCount);
            if (shardDocCount < minDocCount) {
                minDocCount = shardDocCount;
                leastLoadedClient = client;
            }
        }
        if (leastLoadedClient != null) {
            if (isWriteOperation) {
                leastLoadedClient.incrementDocToShard(shardId);
            } else {
                leastLoadedClient.decrementDocFromShard(shardId);
            }
            return leastLoadedClient.getStub();
        } else {
            throw new IllegalStateException("No available clients for shard: " + shardId);
        }
    }

    public ShardStateStore.ShardDocSnapshot snapshotShardDocCounts() {
        ShardStateStore.ShardDocSnapshot snapshot = new ShardStateStore.ShardDocSnapshot();
        for (NodeClient<T> client : clientMap.values()) {
            ShardStateStore.NodeEntry entry = new ShardStateStore.NodeEntry();
            entry.setNodeId(client.getNodeId());

            Map<String, Long> shardCounts = new HashMap<>();
            for (ShardState state : client.getShardStates().values()) {
                shardCounts.put(state.getShardId(), state.getDocumentCount());
            }
            entry.setShards(shardCounts);
            snapshot.getNodes().add(entry);
        }
        return snapshot;
    }

    public void applySnapshot(ShardStateStore.ShardDocSnapshot snapshot) {
        Map<String, ShardStateStore.NodeEntry> byNode = new HashMap<>();
        for (ShardStateStore.NodeEntry e : snapshot.getNodes()) {
            byNode.put(e.getNodeId(), e);
        }

        for (NodeClient<T> client : clientMap.values()) {
            ShardStateStore.NodeEntry entry = byNode.get(client.getNodeId());
            if (entry == null) {
                continue;
            }
            for (Map.Entry<String, Long> shardCount : entry.getShards().entrySet()) {
                String shardId = shardCount.getKey();
                long count = shardCount.getValue();
                ShardState state = client.getOrCreateShardState(shardId);
                state.getDocCount().set(count);
            }
        }
    }

    public void shutdown() {
        for (ManagedChannel channel : this.channels) {
            if (!channel.isShutdown() && !channel.isTerminated()) {
                channel.shutdown();
            }
        }
    }
}