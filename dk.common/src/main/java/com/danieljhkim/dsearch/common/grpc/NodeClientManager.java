package com.danieljhkim.dsearch.common.grpc;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.enums.RoutingStrategy;
import com.danieljhkim.dsearch.common.loadbalancer.RoundRobin;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.Getter;

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
    private final Map<String, NodeClient<T>> stubsMap;
    private final List<ManagedChannel> channels;
    private final RoutingStrategy routingStrategy;

    public NodeClientManager(Map<String, NodeClient<T>> stubsMap, RoutingStrategy routingStrategy) {
        this.channels = stubsMap.values().stream()
                .map(NodeClient::getChannel)
                .toList();
        List<T> stubs = stubsMap.values().stream()
                .map(NodeClient::getStub)
                .toList();
        this.rr = new RoundRobin<>(stubs);
        this.stubsMap = Objects.requireNonNull(stubsMap, "stubsMap must not be null");
        this.routingStrategy = routingStrategy;
    }

    public static <T> NodeClientManager<T> fromConfig(
            AppConfig.NodeGroupConfig appConfig,
            Function<ManagedChannel, T> clientFactory
    ) {
        Map<String, NodeClient<T>> stubsMap =
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
        return new NodeClientManager<>(stubsMap, appConfig.getRoutingStrategy());
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
        for (NodeClient<T> client : stubsMap.values()) {
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

    public void shutdown() {
        for (ManagedChannel channel : this.channels) {
            if (!channel.isShutdown() && !channel.isTerminated()) {
                channel.shutdown();
            }
        }
    }
}