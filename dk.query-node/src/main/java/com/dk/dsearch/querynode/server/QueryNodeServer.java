package com.dk.dsearch.querynode.server;

import com.dk.dsearch.common.grpc.GlobalExceptionInterceptor;
import com.dk.dsearch.querynode.grpc.QueryServiceImpl;
import com.dk.dsearch.querynode.search.SearchExecutor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;

import java.io.IOException;

public class QueryNodeServer {

    private final Server server;

    public QueryNodeServer(int port, SearchExecutor searchExecutor) {
        QueryServiceImpl queryService = new QueryServiceImpl(searchExecutor);
        ServerServiceDefinition interceptedService = ServerInterceptors.intercept(queryService, new GlobalExceptionInterceptor());

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
