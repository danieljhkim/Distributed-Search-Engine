package com.dk.search.indexnode;

import com.dk.search.indexnode.grpc.IndexServiceImpl;
import com.dk.search.indexnode.index.IndexManager;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class IndexNodeApplication {

    private static final Logger LOGGER = Logger.getLogger(IndexNodeApplication.class.getName());

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = Integer.parseInt(System.getenv().getOrDefault("INDEX_NODE_PORT", "5000"));
        String baseDirStr = System.getenv().getOrDefault("INDEX_NODE_BASE_DIR", "./data/index-node");

        Path baseDir = Path.of(baseDirStr);

        IndexManager indexManager = new IndexManager(baseDir);
        IndexServiceImpl indexService = new IndexServiceImpl(indexManager);

        Server server = ServerBuilder
                .forPort(port)
                .addService(indexService)
                .build()
                .start();

        LOGGER.info("IndexNode gRPC server started on port " + port + ", baseDir=" + baseDir);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutting down IndexNode gRPC server...");
            try {
                indexManager.close();
                server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                LOGGER.log(Level.SEVERE, "Error during shutdown", e);
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Error closing IndexManager", e);
            }
        }));

        server.awaitTermination();
    }
}