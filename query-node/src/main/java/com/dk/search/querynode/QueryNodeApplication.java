package com.dk.search.querynode;

import com.dk.search.querynode.search.SearchExecutor;
import com.dk.search.querynode.server.QueryNodeServer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

public class QueryNodeApplication {

    private static final Logger LOGGER = Logger.getLogger(QueryNodeApplication.class.getName());

    public static void main(String[] args) throws IOException, InterruptedException {
        // Config via env or defaults
        int port = Integer.parseInt(System.getenv().getOrDefault("QUERY_NODE_PORT", "6000"));
        String baseDirStr = System.getenv().getOrDefault("QUERY_NODE_BASE_DIR", "./data/query-node");
        Path baseDir = Path.of(baseDirStr);

        SearchExecutor searchExecutor = new SearchExecutor(baseDir);
        QueryNodeServer queryNodeServer = new QueryNodeServer(port, searchExecutor);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutting down QueryNode gRPC server...");
            try {
                searchExecutor.close();
                queryNodeServer.shutdown();
            } catch (InterruptedException e) {
                LOGGER.log(Level.SEVERE, "Error during shutdown", e);
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Error closing SearchExecutor", e);
            }
        }));

        LOGGER.info("QueryNode gRPC server started on port " + port + ", baseDir=" + baseDir);
        queryNodeServer.start();
    }
}