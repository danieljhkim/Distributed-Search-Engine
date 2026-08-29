package com.danieljhkim.dsearch.querynode.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.querynode.grpc.BaseIndexService;
import com.danieljhkim.dsearch.querynode.search.SearchExecutor;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class QueryNodeServerTest {

    @Test
    void startsGrpcAndMetricsAndStopsBoth() throws Exception {
        int grpcPort = freePort();
        int metricsPort = freePort();
        QueryNodeServer server = newServer(grpcPort, metricsPort);
        ExecutorService starter = Executors.newSingleThreadExecutor();
        Future<?> started = starter.submit(() -> start(server));
        try {
            assertTrue(awaitTcpPort(grpcPort));
            HttpResponse<String> metrics = awaitMetrics(metricsPort);
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.body().contains("process_cpu_seconds"));
        } finally {
            server.shutdown();
            started.get(5, TimeUnit.SECONDS);
            starter.shutdownNow();
        }
    }

    @Test
    void rollsBackGrpcWhenMetricsPortCannotBeBoundAndAllowsPortReuse() throws Exception {
        int grpcPort = freePort();
        int metricsPort = freePort();
        try (ServerSocket occupiedMetricsPort = new ServerSocket(metricsPort)) {
            QueryNodeServer failed = newServer(grpcPort, metricsPort);
            assertThrows(IOException.class, () -> failed.start());
        }

        QueryNodeServer replacement = newServer(grpcPort, freePort());
        startAndStop(replacement, grpcPort);
    }

    @Test
    void rollsBackWhenGrpcPortCannotBeBound() throws Exception {
        int grpcPort = freePort();
        try (ServerSocket occupiedGrpcPort = new ServerSocket(grpcPort)) {
            QueryNodeServer failed = newServer(grpcPort, freePort());
            assertThrows(IOException.class, () -> failed.start());
        }

        QueryNodeServer replacement = newServer(grpcPort, freePort());
        startAndStop(replacement, grpcPort);
    }

    @Test
    void shutdownIsIdempotentBeforeStart() throws Exception {
        QueryNodeServer server = newServer(freePort(), freePort());

        server.shutdown();
        server.shutdown();
    }

    private static QueryNodeServer newServer(int grpcPort, int metricsPort) {
        return new QueryNodeServer(
                grpcPort, metricsPort, mock(SearchExecutor.class), mock(BaseIndexService.class), new AppConfig());
    }

    private static void startAndStop(QueryNodeServer server, int grpcPort) throws Exception {
        ExecutorService starter = Executors.newSingleThreadExecutor();
        Future<?> started = starter.submit(() -> start(server));
        try {
            assertTrue(awaitTcpPort(grpcPort));
        } finally {
            server.shutdown();
            started.get(5, TimeUnit.SECONDS);
            starter.shutdownNow();
        }
    }

    private static void start(QueryNodeServer server) {
        try {
            server.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static boolean awaitTcpPort(int port) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try (Socket ignored = new Socket("localhost", port)) {
                return true;
            } catch (IOException e) {
                Thread.yield();
            }
        }
        return false;
    }

    private static HttpResponse<String> awaitMetrics(int port) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        IOException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                return client.send(
                        HttpRequestBuilder.get("http://localhost:" + port + "/metrics"),
                        HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                lastFailure = e;
                Thread.yield();
            }
        }
        throw lastFailure;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class HttpRequestBuilder {
        private HttpRequestBuilder() {}

        static java.net.http.HttpRequest get(String uri) {
            return java.net.http.HttpRequest.newBuilder(URI.create(uri)).GET().build();
        }
    }
}
