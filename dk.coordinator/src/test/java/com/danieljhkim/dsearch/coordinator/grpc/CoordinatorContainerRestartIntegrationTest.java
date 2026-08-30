package com.danieljhkim.dsearch.coordinator.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.danieljhkim.dsearch.proto.cluster.ClusterServiceGrpc;
import com.danieljhkim.dsearch.proto.cluster.GetShardMapRequest;
import com.danieljhkim.dsearch.proto.cluster.GetShardMapResponse;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import com.danieljhkim.dsearch.proto.cluster.RegisterNodeRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class CoordinatorContainerRestartIntegrationTest {

    private static final String STATE_PATH = "/data/coordinator-topology.properties";

    @Test
    void recreatingCoordinatorContainerRetainsTopologyAndEpoch() throws Exception {
        Assumptions.assumeTrue(
                Boolean.getBoolean("dsearch.docker.it"),
                "set -Ddsearch.docker.it=true to run the Docker container integration test");
        Assumptions.assumeTrue(dockerDaemonAvailable(), "Docker daemon is unavailable");

        Path repositoryRoot = repositoryRoot();
        String suffix = UUID.randomUUID().toString();
        String image = "dsearch-coordinator-it-" + suffix;
        String volume = "dsearch-coordinator-state-it-" + suffix;
        String firstContainer = "dsearch-coordinator-it-first-" + suffix;
        String secondContainer = "dsearch-coordinator-it-second-" + suffix;
        int grpcPort = freePort();
        int healthPort = freePort();
        while (healthPort == grpcPort) {
            healthPort = freePort();
        }

        try {
            run(List.of(
                    "docker",
                    "build",
                    "--quiet",
                    "--tag",
                    image,
                    "--file",
                    repositoryRoot.resolve("dk.coordinator/Dockerfile").toString(),
                    repositoryRoot.toString()));
            run(List.of("docker", "volume", "create", volume));
            runCoordinator(image, firstContainer, volume, grpcPort, healthPort);

            GetShardMapResponse beforeRestart;
            ManagedChannel channel = channel(grpcPort);
            try {
                ClusterServiceGrpc.ClusterServiceBlockingStub client = ClusterServiceGrpc.newBlockingStub(channel);
                waitFor(client, response -> true, Duration.ofSeconds(30));
                long registeredVersion = client.registerNode(registerRequest()).getTopologyVersion();
                beforeRestart = client.getShardMap(GetShardMapRequest.getDefaultInstance());
                assertEquals(registeredVersion, beforeRestart.getTopologyVersion());
                assertEquals(
                        "index/container-index-0",
                        beforeRestart.getShardLocations(0).getShardId());
            } finally {
                shutdown(channel);
            }

            run(List.of("docker", "rm", "--force", firstContainer));
            runCoordinator(image, secondContainer, volume, grpcPort, healthPort);

            channel = channel(grpcPort);
            try {
                ClusterServiceGrpc.ClusterServiceBlockingStub client = ClusterServiceGrpc.newBlockingStub(channel);
                GetShardMapResponse afterRestart = waitFor(
                        client,
                        response -> response.getTopologyVersion() == beforeRestart.getTopologyVersion(),
                        Duration.ofSeconds(30));
                assertEquals(beforeRestart.getTopologyEpoch(), afterRestart.getTopologyEpoch());
                assertEquals(beforeRestart.getShardLocationsList(), afterRestart.getShardLocationsList());
            } finally {
                shutdown(channel);
            }
        } finally {
            runIgnoringFailure(List.of("docker", "rm", "--force", firstContainer));
            runIgnoringFailure(List.of("docker", "rm", "--force", secondContainer));
            runIgnoringFailure(List.of("docker", "volume", "rm", "--force", volume));
            runIgnoringFailure(List.of("docker", "image", "rm", "--force", image));
        }
    }

    private static void runCoordinator(String image, String container, String volume, int grpcPort, int healthPort)
            throws IOException, InterruptedException {
        run(List.of(
                "docker",
                "run",
                "--detach",
                "--name",
                container,
                "--publish",
                grpcPort + ":7000",
                "--publish",
                healthPort + ":8080",
                "--volume",
                volume + ":/data",
                "--env",
                "COORDINATOR_PORT=7000",
                "--env",
                "COORDINATOR_HEALTH_PORT=8080",
                "--env",
                "APP_CONFIG_PATH=app-config.docker.yaml",
                "--env",
                "COORDINATOR_STATE_FILE=" + STATE_PATH,
                image));
    }

    private static ManagedChannel channel(int grpcPort) {
        return ManagedChannelBuilder.forAddress("localhost", grpcPort)
                .usePlaintext()
                .build();
    }

    private static void shutdown(ManagedChannel channel) throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
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
        throw new AssertionError("Coordinator container did not reach expected topology", lastFailure);
    }

    private static RegisterNodeRequest registerRequest() {
        return RegisterNodeRequest.newBuilder()
                .setNodeId("container-index-0")
                .setHost("localhost")
                .setPort(5000)
                .setHealthPort(5100)
                .setRole(NodeRole.NODE_ROLE_INDEX)
                .build();
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("docker-compose.yml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not find repository root containing docker-compose.yml");
    }

    private static boolean dockerDaemonAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "info", "--format", "{{.ServerVersion}}")
                    .redirectErrorStream(true)
                    .start();
            boolean completed = process.waitFor(10, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor();
                return false;
            }
            return process.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean completed = process.waitFor(5, TimeUnit.MINUTES);
        if (!completed) {
            process.destroyForcibly();
            process.waitFor();
            throw new AssertionError("Command timed out: " + String.join(" ", command));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), () -> "Command failed: " + String.join(" ", command) + "\n" + output);
    }

    private static void runIgnoringFailure(List<String> command) {
        try {
            run(command);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException | AssertionError ignored) {
            // Cleanup must not hide the original test failure.
        }
    }
}
