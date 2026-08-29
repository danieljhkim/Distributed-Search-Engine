package com.danieljhkim.dsearch.coordinator.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.proto.cluster.ClusterServiceGrpc;
import com.danieljhkim.dsearch.proto.cluster.GetShardMapRequest;
import com.danieljhkim.dsearch.proto.cluster.GetShardMapResponse;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import com.danieljhkim.dsearch.proto.cluster.RegisterNodeRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoordinatorProcessIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void separateCoordinatorProcessPreservesAndAdvancesAuthoritativeTopology() throws Exception {
        int grpcPort = freePort();
        int healthPort = freePort();
        while (healthPort == grpcPort) {
            healthPort = freePort();
        }
        Path stateFile = tempDir.resolve("process-coordinator.properties");
        Path logFile = tempDir.resolve("process-coordinator.log");
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", grpcPort)
                .usePlaintext()
                .build();
        ClusterServiceGrpc.ClusterServiceBlockingStub client = ClusterServiceGrpc.newBlockingStub(channel);

        Process first = null;
        Process recovered = null;
        try {
            first = startCoordinator(grpcPort, healthPort, stateFile, logFile);
            waitFor(client, response -> true, Duration.ofSeconds(10));
            long registeredVersion = client.registerNode(registerRequest()).getTopologyVersion();
            GetShardMapResponse beforeRestart = client.getShardMap(GetShardMapRequest.getDefaultInstance());
            assertEquals(registeredVersion, beforeRestart.getTopologyVersion());
            assertEquals(
                    "index/process-index-0", beforeRestart.getShardLocations(0).getShardId());

            stop(first);
            first = null;
            StatusRuntimeException unavailable = assertThrows(
                    StatusRuntimeException.class, () -> client.withDeadlineAfter(250, TimeUnit.MILLISECONDS)
                            .getShardMap(GetShardMapRequest.getDefaultInstance()));
            assertEquals(Status.Code.UNAVAILABLE, unavailable.getStatus().getCode());

            recovered = startCoordinator(grpcPort, healthPort, stateFile, logFile);
            GetShardMapResponse afterRestart = waitFor(
                    client,
                    response -> response.getTopologyVersion() == beforeRestart.getTopologyVersion(),
                    Duration.ofSeconds(10));
            assertEquals(beforeRestart.getTopologyEpoch(), afterRestart.getTopologyEpoch());
            assertEquals(beforeRestart.getShardLocationsList(), afterRestart.getShardLocationsList());

            GetShardMapResponse afterExpiry = waitFor(
                    client,
                    response -> response.getShardLocationsCount() == 0
                            && response.getTopologyVersion() >= afterRestart.getTopologyVersion() + 2,
                    Duration.ofSeconds(10));
            long reregisteredVersion = client.registerNode(registerRequest()).getTopologyVersion();
            assertTrue(reregisteredVersion > afterExpiry.getTopologyVersion());

            StatusRuntimeException stale = assertThrows(
                    StatusRuntimeException.class,
                    () -> client.getShardMap(GetShardMapRequest.newBuilder()
                            .setMinTopologyVersion(reregisteredVersion + 1)
                            .build()));
            assertEquals(Status.Code.FAILED_PRECONDITION, stale.getStatus().getCode());
        } finally {
            if (first != null) {
                stop(first);
            }
            if (recovered != null) {
                stop(recovered);
            }
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private Process startCoordinator(int grpcPort, int healthPort, Path stateFile, Path logFile) throws IOException {
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                classPath,
                "com.danieljhkim.dsearch.coordinator.CoordinatorApplication");
        builder.environment().put("APP_CONFIG_PATH", "process-app-config.yaml");
        builder.environment().put("COORDINATOR_PORT", Integer.toString(grpcPort));
        builder.environment().put("COORDINATOR_HEALTH_PORT", Integer.toString(healthPort));
        builder.environment().put("COORDINATOR_STATE_FILE", stateFile.toString());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        return builder.start();
    }

    private static GetShardMapResponse waitFor(
            ClusterServiceGrpc.ClusterServiceBlockingStub client,
            Predicate<GetShardMapResponse> predicate,
            Duration timeout)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        StatusRuntimeException lastFailure = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                GetShardMapResponse response = client.withDeadlineAfter(500, TimeUnit.MILLISECONDS)
                        .getShardMap(GetShardMapRequest.getDefaultInstance());
                if (predicate.test(response)) {
                    return response;
                }
            } catch (StatusRuntimeException e) {
                lastFailure = e;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Coordinator did not reach expected topology before timeout", lastFailure);
    }

    private static RegisterNodeRequest registerRequest() {
        return RegisterNodeRequest.newBuilder()
                .setNodeId("process-index-0")
                .setHost("localhost")
                .setPort(5000)
                .setHealthPort(5100)
                .setRole(NodeRole.NODE_ROLE_INDEX)
                .build();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void stop(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        }
    }
}
