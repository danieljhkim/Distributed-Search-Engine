package com.danieljhkim.dsearch.querynode.server;

import com.danieljhkim.dsearch.common.grpc.GlobalExceptionInterceptor;
import com.danieljhkim.dsearch.querynode.grpc.BaseIndexService;
import com.danieljhkim.dsearch.querynode.grpc.QueryServiceImpl;
import com.danieljhkim.dsearch.querynode.search.SearchExecutor;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;

import java.io.IOException;

public class QueryNodeServer {

    private final Server server;

    public QueryNodeServer(int port, SearchExecutor searchExecutor, BaseIndexService indexService) {
        QueryServiceImpl queryService = new QueryServiceImpl(searchExecutor, indexService);
        ServerServiceDefinition interceptedService = ServerInterceptors.intercept(queryService, new GlobalExceptionInterceptor());

        this.server = NettyServerBuilder
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
