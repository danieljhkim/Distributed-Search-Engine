package com.danieljhkim.dsearch.querynode.server;

import com.danieljhkim.dsearch.common.grpc.GlobalExceptionInterceptor;
import com.danieljhkim.dsearch.common.grpc.PrometheusGrpcServerInterceptor;
import com.danieljhkim.dsearch.common.tracing.CorrelationIdServerInterceptor;
import com.danieljhkim.dsearch.querynode.grpc.BaseIndexService;
import com.danieljhkim.dsearch.querynode.grpc.QueryServiceImpl;
import com.danieljhkim.dsearch.querynode.search.SearchExecutor;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.hotspot.DefaultExports;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class QueryNodeServer {

    private static final Logger LOGGER = Logger.getLogger(QueryNodeServer.class.getName());

    private final Server server;
    private HTTPServer metricsServer;

    public QueryNodeServer(int port, SearchExecutor searchExecutor, BaseIndexService indexService) {
        QueryServiceImpl queryService = new QueryServiceImpl(searchExecutor, indexService);
        ServerServiceDefinition interceptedService = ServerInterceptors.intercept(queryService, new GlobalExceptionInterceptor());

        this.server = NettyServerBuilder
                .forPort(port)
                .addService(interceptedService)
                .intercept(new CorrelationIdServerInterceptor())
                .intercept(new PrometheusGrpcServerInterceptor())
                .build();

        startPrometheusMetricsServer(port + 2000);
    }

    public void start() throws IOException, InterruptedException {
        server.start();
        server.awaitTermination();
    }

    private void startPrometheusMetricsServer(int metricsPort) {
        DefaultExports.initialize();
        try {
            this.metricsServer = new HTTPServer(metricsPort);
            LOGGER.info(() -> "Prometheus metrics server started on port " + metricsPort);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to start Prometheus metrics server on port " + metricsPort, e);
        }
    }

    public void shutdown() throws InterruptedException {
        if (metricsServer != null) {
            metricsServer.close();
            LOGGER.info("Prometheus metrics server stopped");
        }
        if (server != null) {
            server.shutdown().awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
