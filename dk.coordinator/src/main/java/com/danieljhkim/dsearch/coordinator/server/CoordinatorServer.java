package com.danieljhkim.dsearch.coordinator.server;

import com.danieljhkim.dsearch.common.grpc.GlobalExceptionInterceptor;
import com.danieljhkim.dsearch.coordinator.cluster.ClusterMembershipService;
import com.danieljhkim.dsearch.coordinator.grpc.ClusterServiceImpl;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import java.io.IOException;

public class CoordinatorServer {

    private final Server server;

    public CoordinatorServer(int port, ClusterMembershipService membershipService) {
        ClusterServiceImpl clusterService = new ClusterServiceImpl(membershipService);
        ServerServiceDefinition interceptedService =
                ServerInterceptors.intercept(clusterService, new GlobalExceptionInterceptor());
        this.server = ServerBuilder.forPort(port).addService(interceptedService).build();
    }

    public void start() throws IOException, InterruptedException {
        server.start();
        server.awaitTermination();
    }

    public void shutdown() throws InterruptedException {
        if (server != null) {
            server.shutdown().awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
