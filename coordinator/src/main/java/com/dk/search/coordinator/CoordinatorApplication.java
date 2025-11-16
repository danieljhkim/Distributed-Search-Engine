package com.dk.search.coordinator;


import com.dk.search.coordinator.cluster.ClusterMembershipService;
import com.dk.search.coordinator.cluster.ShardMap;
import com.dk.search.coordinator.grpc.ClusterServiceImpl;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CoordinatorApplication {

    private static final Logger LOGGER = Logger.getLogger(CoordinatorApplication.class.getName());

    public static void main(String[] args) throws IOException, InterruptedException {
        // Port & shard count from environment (aligned with run_cluster_multi.sh)
        int port = Integer.parseInt(System.getenv().getOrDefault("COORDINATOR_PORT", "7000"));
        int numShards = Integer.parseInt(System.getenv().getOrDefault("NUM_SHARDS", "4"));

        // Initialize core coordinator components
        ClusterMembershipService membershipService = new ClusterMembershipService();
        ShardMap shardMap = new ShardMap(numShards);

        // gRPC service implementation
        ClusterServiceImpl clusterService = new ClusterServiceImpl(membershipService, shardMap);

        Server server = ServerBuilder
                .forPort(port)
                .addService(clusterService)
                .build()
                .start();

        LOGGER.info("Coordinator gRPC server started on port " + port + " with " + numShards + " shard(s)");

        // Graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutting down Coordinator gRPC server...");
            try {
                server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                LOGGER.log(Level.SEVERE, "Interrupted during coordinator shutdown", e);
                Thread.currentThread().interrupt();
            }
        }));

        // Block main thread
        server.awaitTermination();
    }
}