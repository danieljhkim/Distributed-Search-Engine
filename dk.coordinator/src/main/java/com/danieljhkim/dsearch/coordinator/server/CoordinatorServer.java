package com.danieljhkim.dsearch.coordinator.server;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.grpc.GlobalExceptionInterceptor;
import com.danieljhkim.dsearch.common.grpc.GrpcPeerIdentityInterceptor;
import com.danieljhkim.dsearch.common.grpc.GrpcTransportSecurity;
import com.danieljhkim.dsearch.coordinator.cluster.ClusterMembershipService;
import com.danieljhkim.dsearch.coordinator.grpc.ClusterServiceImpl;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import java.io.IOException;

public class CoordinatorServer {

    private final Server server;

    public CoordinatorServer(int port, ClusterMembershipService membershipService, AppConfig appConfig) {
        GrpcTransportSecurity transportSecurity = GrpcTransportSecurity.from(appConfig);
        ClusterServiceImpl clusterService = new ClusterServiceImpl(membershipService);
        ServerServiceDefinition interceptedService =
                ServerInterceptors.intercept(clusterService, new GlobalExceptionInterceptor());
        this.server = transportSecurity
                .serverBuilder(port)
                .addService(interceptedService)
                .intercept(new GrpcPeerIdentityInterceptor(transportSecurity))
                .build();
    }

    public void start() throws IOException, InterruptedException {
        startAsync();
        awaitTermination();
    }

    public void startAsync() throws IOException {
        server.start();
    }

    public int getPort() {
        return server.getPort();
    }

    public void awaitTermination() throws InterruptedException {
        server.awaitTermination();
    }

    public void shutdown() throws InterruptedException {
        if (server != null) {
            server.shutdown().awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
