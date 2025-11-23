package com.dk.search.querynode;

import com.dk.search.common.config.GrpcChannelConfig;
import com.dk.search.proto.index.IndexServiceGrpc;
import com.dk.search.querynode.grpc.IndexService;
import com.dk.search.querynode.search.SearchExecutor;
import com.dk.search.querynode.server.QueryNodeServer;
import io.grpc.ManagedChannel;

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
        ManagedChannel channel = GrpcChannelConfig.getIndexChannel();
        IndexService indexService = new IndexService(IndexServiceGrpc.newBlockingStub(channel));
        SearchExecutor searchExecutor = new SearchExecutor(indexService);
        QueryNodeServer queryNodeServer = new QueryNodeServer(port, searchExecutor);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutting down QueryNode gRPC server...");
            try {
                queryNodeServer.shutdown();
            } catch (InterruptedException e) {
                LOGGER.log(Level.SEVERE, "Error during shutdown", e);
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error closing", e);
            }
        }));

        LOGGER.info("QueryNode gRPC server started on port " + port + ", baseDir=" + baseDir);
        queryNodeServer.start();
    }
}