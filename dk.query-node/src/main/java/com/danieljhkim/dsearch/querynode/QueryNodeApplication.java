package com.danieljhkim.dsearch.querynode;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.config.ConfigLoader;
import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;
import com.danieljhkim.dsearch.querynode.grpc.BaseIndexService;
import com.danieljhkim.dsearch.querynode.grpc.IndexService;
import com.danieljhkim.dsearch.querynode.search.SearchExecutor;
import com.danieljhkim.dsearch.querynode.server.QueryNodeServer;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class QueryNodeApplication {

    private static final Logger LOGGER = Logger.getLogger(QueryNodeApplication.class.getName());

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = Integer.parseInt(System.getenv().getOrDefault("QUERY_NODE_PORT", "6000"));
        AppConfig appConfig = ConfigLoader.load("app-config.yaml");
        NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager = NodeClientManager.forShards(appConfig, IndexServiceGrpc::newBlockingStub);
        SearchExecutor searchExecutor = new SearchExecutor(nodeClientManager);
        BaseIndexService indexService = new IndexService(nodeClientManager);
        QueryNodeServer queryNodeServer = new QueryNodeServer(port, searchExecutor, indexService);

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

        LOGGER.info("QueryNode gRPC server started on port " + port);
        queryNodeServer.start();
    }
}