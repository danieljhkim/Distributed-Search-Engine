package com.danieljhkim.dsearch.common.config;

import com.danieljhkim.dsearch.common.grpc.GrpcTransportSecurity;
import io.grpc.ManagedChannel;
import java.io.IOException;

public class GrpcChannelConfig {

    public GrpcChannelConfig() {}

    public static ManagedChannel getIndexChannel() {
        String host = System.getenv().getOrDefault("INDEX_NODE_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("INDEX_NODE_PORT", "5000"));
        return newChannel(host, port);
    }

    public static ManagedChannel getQueryChannel() {
        String host = System.getenv().getOrDefault("QUERY_NODE_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("QUERY_NODE_PORT", "6000"));
        return newChannel(host, port);
    }

    private static ManagedChannel newChannel(String host, int port) {
        try {
            return GrpcTransportSecurity.from(ConfigLoader.load()).newChannel(host, port);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load gRPC security configuration", e);
        }
    }
}
