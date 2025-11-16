package com.dk.search.coordinator.server;

import com.dk.search.common.grpc.GlobalExceptionInterceptor;
import com.dk.search.coordinator.cluster.ClusterMembershipService;
import com.dk.search.coordinator.cluster.ShardMap;
import com.dk.search.coordinator.grpc.ClusterServiceImpl;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;

import java.io.IOException;

public class CoordinatorServer {

    private final Server server;

    public CoordinatorServer(int port, ShardMap shardMap) {

        ClusterMembershipService membershipService = new ClusterMembershipService();
        ClusterServiceImpl clusterService = new ClusterServiceImpl(membershipService, shardMap);
        ServerServiceDefinition interceptedService = ServerInterceptors.intercept(clusterService, new GlobalExceptionInterceptor());

        this.server = ServerBuilder
                .forPort(port)
                .addService(interceptedService)
                .build();
    }

    public void start() throws IOException, InterruptedException {
        server.start();
        server.awaitTermination();
    }

    public void shutdown() throws InterruptedException {
        if (server != null) {
            server.shutdown().awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
