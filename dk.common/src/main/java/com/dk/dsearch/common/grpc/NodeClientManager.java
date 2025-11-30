package com.dk.dsearch.common.grpc;

import com.dk.dsearch.common.config.AppConfig;
import com.dk.dsearch.common.loadbalancer.RoundRobin;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.Getter;

import java.util.ArrayList;
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
    private final List<T> stubs;
    private final List<ManagedChannel> channels;
    @Getter
    private Map<String, T> stubsMap;

    public NodeClientManager(List<T> stubs, List<ManagedChannel> channels) {
        if (stubs == null || stubs.isEmpty()) {
            throw new IllegalArgumentException("stubs must not be empty");
        }
        this.stubs = List.copyOf(stubs);
        this.rr = new RoundRobin<>(this.stubs);
        this.channels = List.copyOf(Objects.requireNonNull(channels, "channels must not be null"));
        this.stubsMap = Map.of(); // no shard mapping in this ctor
    }

    public NodeClientManager(Map<String, T> stubsMap, List<ManagedChannel> channels) {
        this(stubsMap.values().stream().toList(), channels);
        this.stubsMap = Map.copyOf(Objects.requireNonNull(stubsMap, "stubsMap must not be null"));
    }

    public static <T> NodeClientManager<T> forPorts(
            List<Integer> ports,
            String host,
            Function<ManagedChannel, T> clientFactory
    ) {
        List<ManagedChannel> ch = new ArrayList<>();
        List<T> stubs = ports.stream()
                .map(port -> {
                    ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                            .usePlaintext()
                            .build();
                    ch.add(channel);
                    return clientFactory.apply(channel);
                })
                .toList();

        return new NodeClientManager<>(stubs, ch);
    }

    public static <T> NodeClientManager<T> forShards(
            AppConfig appConfig,
            Function<ManagedChannel, T> clientFactory
    ) {
        List<ManagedChannel> ch = new ArrayList<>();
        Map<String, T> stubsMap =
                appConfig.getCluster().getIndexNodes().stream()
                        .collect(Collectors.toMap(
                                node -> String.valueOf(node.getId()),
                                node -> {
                                    ManagedChannel channel = ManagedChannelBuilder
                                            .forAddress(node.getHost(), node.getPort())
                                            .usePlaintext()
                                            .build();
                                    ch.add(channel);
                                    return clientFactory.apply(channel);
                                }
                        ));

        return new NodeClientManager<>(stubsMap, ch);
    }

    /**
     * Get a client/stub for the next node in round-robin order.
     */
    public T nextClient() {
        return rr.next();
    }

    public void shutdown() {
        for (ManagedChannel channel : channels) {
            if (!channel.isShutdown() && !channel.isTerminated()) {
                channel.shutdown();
            }
        }
    }
}