package com.dk.search.common.loadbalancer;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.logging.Logger;

public class NodeClientManager<T> {

    private static final Logger LOGGER = Logger.getLogger(NodeClientManager.class.getName());

    private final List<ManagedChannel> channels;
    private final RoundRobin<ManagedChannel> rr;
    private final Function<ManagedChannel, T> clientFactory;

    public NodeClientManager(List<ManagedChannel> channels, Function<ManagedChannel, T> clientFactory) {
        if (channels == null || channels.isEmpty()) {
            throw new IllegalArgumentException("channels must not be empty");
        }
        this.channels = List.copyOf(channels);
        this.rr = new RoundRobin<>(this.channels);
        this.clientFactory = Objects.requireNonNull(clientFactory);
    }

    public static <T> NodeClientManager<T> forPorts(
            List<Integer> ports,
            String host,
            Function<ManagedChannel, T> clientFactory
    ) {
        List<ManagedChannel> channels = ports.stream()
                .map(port -> ManagedChannelBuilder.forAddress(host, port)
                        .usePlaintext()
                        .build())
                .toList();
        return new NodeClientManager<>(channels, clientFactory);
    }

    /**
     * Get a client/stub for the next node in round-robin order.
     */
    public T nextClient() {
        ManagedChannel channel = rr.next();
        return clientFactory.apply(channel);
    }

    public void shutdown() {
        channels.forEach(ManagedChannel::shutdown);
    }
}