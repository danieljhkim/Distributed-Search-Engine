package com.danieljhkim.dsearch.indexnode.server;

import com.danieljhkim.dsearch.common.grpc.GlobalExceptionInterceptor;
import com.danieljhkim.dsearch.common.grpc.PrometheusGrpcServerInterceptor;
import com.danieljhkim.dsearch.common.tracing.CorrelationIdServerInterceptor;
import com.danieljhkim.dsearch.indexnode.grpc.IndexServiceImpl;
import com.danieljhkim.dsearch.indexnode.index.IndexManager;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.hotspot.DefaultExports;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class IndexNodeServer {

    private static final Logger LOGGER = Logger.getLogger(IndexNodeServer.class.getName());

    private final Server server;
    private volatile boolean grpcServerStarted;
    private HTTPServer metricsServer;

    public IndexNodeServer(int port, IndexManager indexManager) {
        this(port, port + 4000, indexManager);
    }

    IndexNodeServer(int port, int metricsPort, IndexManager indexManager) {
        IndexServiceImpl indexService = new IndexServiceImpl(indexManager);
        ServerServiceDefinition interceptedService =
                ServerInterceptors.intercept(indexService, new GlobalExceptionInterceptor());

        this.server = NettyServerBuilder.forPort(port)
                .addService(interceptedService)
                .intercept(new CorrelationIdServerInterceptor())
                .intercept(new PrometheusGrpcServerInterceptor())
                .build();

        startPrometheusMetricsServer(metricsPort);
    }

    public void start() throws IOException, InterruptedException {
        try {
            startAsync();
            awaitTermination();
        } catch (IOException | RuntimeException e) {
            rollbackStartup();
            throw e;
        } catch (InterruptedException e) {
            rollbackStartup();
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    public void startAsync() throws IOException {
        server.start();
        grpcServerStarted = true;
    }

    public void awaitTermination() throws InterruptedException {
        server.awaitTermination();
    }

    private void startPrometheusMetricsServer(int metricsPort) {
        DefaultExports.initialize();
        try {
            this.metricsServer = new HTTPServer(metricsPort);
            LOGGER.info(() -> "Prometheus metrics server started on port " + metricsPort);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to start Prometheus metrics server on port " + metricsPort, e);
        }
    }

    public void shutdown() throws InterruptedException {
        if (metricsServer != null) {
            metricsServer.close();
            metricsServer = null;
            LOGGER.info("Prometheus metrics server stopped");
        }
        if (server != null) {
            server.shutdown().awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS);
            LOGGER.info("IndexNodeServer stopped");
        }
    }

    int grpcPort() {
        return grpcServerStarted ? server.getPort() : -1;
    }

    int metricsPort() {
        return metricsServer == null ? -1 : metricsServer.getPort();
    }

    private void rollbackStartup() {
        grpcServerStarted = false;
        if (metricsServer != null) {
            metricsServer.close();
            metricsServer = null;
        }
        if (!server.isShutdown()) {
            server.shutdownNow();
        }
    }
}
