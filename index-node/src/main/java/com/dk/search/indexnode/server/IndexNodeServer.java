package com.dk.search.indexnode.server;

import com.dk.search.indexnode.grpc.GlobalExceptionInterceptor;
import com.dk.search.indexnode.grpc.IndexServiceImpl;
import com.dk.search.indexnode.index.IndexManager;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;

import java.io.IOException;

public class IndexNodeServer {

    private final Server server;

    public IndexNodeServer(int port, IndexManager indexManager) {
        IndexServiceImpl indexService = new IndexServiceImpl(indexManager);
        ServerServiceDefinition interceptedService = ServerInterceptors.intercept(indexService, new GlobalExceptionInterceptor());

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
