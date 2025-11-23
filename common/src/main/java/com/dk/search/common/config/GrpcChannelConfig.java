package com.dk.search.common.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

public class GrpcChannelConfig {

    public GrpcChannelConfig() {
    }

    public static ManagedChannel getIndexChannel() {
        String host = System.getenv().getOrDefault("INDEX_NODE_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("INDEX_NODE_PORT", "5000"));
        return ManagedChannelBuilder
                .forAddress(host, port)
                .usePlaintext()
                .build();
    }

    public static ManagedChannel getQueryChannel() {
        String host = System.getenv().getOrDefault("QUERY_NODE_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("QUERY_NODE_PORT", "6000"));
        return ManagedChannelBuilder
                .forAddress(host, port)
                .usePlaintext()
                .build();
    }
}
