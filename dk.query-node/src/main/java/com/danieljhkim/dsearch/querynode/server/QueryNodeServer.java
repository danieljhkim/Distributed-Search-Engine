package com.danieljhkim.dsearch.querynode.server;

import com.danieljhkim.dsearch.common.config.AppConfig;
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
import java.util.logging.Logger;

public class QueryNodeServer {

    private static final Logger LOGGER = Logger.getLogger(QueryNodeServer.class.getName());

    private final Server server;
    private HTTPServer metricsServer;
    private final int prometheusPort;

    public QueryNodeServer(
            int port, SearchExecutor searchExecutor, BaseIndexService indexService, AppConfig appConfig) {
        this(port, port + 2000, searchExecutor, indexService, appConfig);
    }

    QueryNodeServer(
            int port,
            int prometheusPort,
            SearchExecutor searchExecutor,
            BaseIndexService indexService,
            AppConfig appConfig) {
        AppConfig.RequestLimitsConfig requestLimits = appConfig.getRequestLimits() != null
                ? appConfig.getRequestLimits()
                : new AppConfig.RequestLimitsConfig();
        QueryServiceImpl queryService = new QueryServiceImpl(searchExecutor, indexService, requestLimits);
        ServerServiceDefinition interceptedService =
                ServerInterceptors.intercept(queryService, new GlobalExceptionInterceptor());

        this.server = NettyServerBuilder.forPort(port)
                .maxInboundMessageSize(Math.max(1, requestLimits.getMaxGrpcInboundBytes()))
                .addService(interceptedService)
                .intercept(new CorrelationIdServerInterceptor())
                .intercept(new PrometheusGrpcServerInterceptor())
                .build();
        this.prometheusPort = prometheusPort;
    }

    public void start() throws IOException, InterruptedException {
        try {
            startAsync();
            awaitTermination();
        } catch (IOException | RuntimeException e) {
            shutdownResources();
            throw e;
        }
    }

    public void startAsync() throws IOException {
        server.start();
        startPrometheusMetricsServer(this.prometheusPort);
    }

    public void awaitTermination() throws InterruptedException {
        server.awaitTermination();
    }

    private void startPrometheusMetricsServer(int metricsPort) throws IOException {
        DefaultExports.initialize();
        this.metricsServer = new HTTPServer(metricsPort);
        LOGGER.info(() -> "Prometheus metrics server started on port " + metricsPort);
    }

    public void shutdown() throws InterruptedException {
        shutdownResources();
    }

    private void shutdownResources() throws InterruptedException {
        if (metricsServer != null) {
            metricsServer.close();
            metricsServer = null;
            LOGGER.info("Prometheus metrics server stopped");
        }
        if (server != null) {
            server.shutdown().awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
