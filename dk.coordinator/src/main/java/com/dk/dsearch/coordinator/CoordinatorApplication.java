package com.dk.dsearch.coordinator;


import com.dk.dsearch.coordinator.cluster.ShardMap;
import com.dk.dsearch.coordinator.server.CoordinatorServer;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CoordinatorApplication {

    private static final Logger LOGGER = Logger.getLogger(CoordinatorApplication.class.getName());

    public static void main(String[] args) throws IOException, InterruptedException {

        int port = Integer.parseInt(System.getenv().getOrDefault("COORDINATOR_PORT", "7000"));
        int numShards = Integer.parseInt(System.getenv().getOrDefault("NUM_SHARDS", "4"));

        ShardMap shardMap = new ShardMap(numShards);
        CoordinatorServer server = new CoordinatorServer(port, shardMap);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutting down Coordinator gRPC server...");
            try {
                server.shutdown();
            } catch (InterruptedException e) {
                LOGGER.log(Level.SEVERE, "Interrupted during coordinator shutdown", e);
                Thread.currentThread().interrupt();
            }
        }));

        LOGGER.info("Coordinator gRPC server started on port " + port + " with " + numShards + " shard(s)");
        server.start();
    }
}