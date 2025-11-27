package com.dk.dsearch.indexnode;

import com.dk.dsearch.indexnode.index.IndexManager;
import com.dk.dsearch.indexnode.server.IndexNodeServer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

public class IndexNodeApplication {

    private static final Logger LOGGER = Logger.getLogger(IndexNodeApplication.class.getName());

    public static void main(String[] args) throws IOException, InterruptedException {

        int port = Integer.parseInt(System.getenv().getOrDefault("INDEX_NODE_PORT", "5000"));
        String nodeId = System.getenv().getOrDefault("INDEX_NODE_ID", "index-node-0");
        String baseDirStr = System.getenv().getOrDefault("INDEX_NODE_BASE_DIR", "./data/" + nodeId);
        Path baseDir = Path.of(baseDirStr);

        IndexManager indexManager = new IndexManager(baseDir);
        IndexNodeServer indexNodeServer = new IndexNodeServer(port, indexManager);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutting down IndexNode gRPC server...");
            try {
                indexManager.close();
                indexNodeServer.shutdown();
            } catch (InterruptedException e) {
                LOGGER.log(Level.SEVERE, "Error during shutdown", e);
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Error closing IndexManager", e);
            }
        }));

        LOGGER.info("IndexNode gRPC server started on port " + port + ", baseDir=" + baseDir);
        indexNodeServer.start();
    }
}