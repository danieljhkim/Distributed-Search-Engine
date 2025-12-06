package com.danieljhkim.dsearch.indexnode.server;

import com.danieljhkim.dsearch.common.grpc.GlobalExceptionInterceptor;
import com.danieljhkim.dsearch.indexnode.grpc.IndexServiceImpl;
import com.danieljhkim.dsearch.indexnode.index.IndexManager;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;

import java.io.IOException;

public class IndexNodeServer {

    private final Server server;

    public IndexNodeServer(int port, IndexManager indexManager) {
        IndexServiceImpl indexService = new IndexServiceImpl(indexManager);
        ServerServiceDefinition interceptedService = ServerInterceptors.intercept(indexService, new GlobalExceptionInterceptor());
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
            server.shutdown().awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
