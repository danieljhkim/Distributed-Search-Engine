package com.danieljhkim.dsearch.common.grpc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.proto.cluster.ClusterServiceGrpc;
import com.danieljhkim.dsearch.proto.cluster.GetShardMapRequest;
import com.danieljhkim.dsearch.proto.cluster.GetShardMapResponse;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GrpcTransportSecurityTest {

    @TempDir
    Path tempDir;

    @Test
    void trustedMutualTlsPeersCompleteRpc() throws Exception {
        try (CertificateResource trusted = certificate("trusted")) {
            AppConfig config = productionConfig(trusted);
            try (RunningServer server = start(config);
                    ManagedChannelResource channel = connect(config, "localhost", server.port())) {
                assertDoesNotThrow(() -> invoke(channel.channel()));
            }
        }
    }

    @Test
    void certificateSpiffeSanProducesNodeIdentity() throws Exception {
        try (CertificateResource trusted = certificate("trusted");
                var input = Files.newInputStream(trusted.certificatePath())) {
            X509Certificate certificate =
                    (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);

            assertEquals(
                    GrpcPeerIdentity.node(NodeRole.NODE_ROLE_INDEX, "node-a"),
                    GrpcPeerIdentity.fromCertificate(certificate));
        }
    }

    @Test
    void serverRejectsClientCertificateFromUnknownCa() throws Exception {
        try (CertificateResource trusted = certificate("trusted");
                CertificateResource unknown = certificate("rotated")) {
            AppConfig serverConfig = productionConfig(trusted);
            AppConfig clientConfig = productionConfig(unknown);
            clientConfig
                    .getGrpcSecurity()
                    .setTrustCertificateCollectionPath(trusted.certificate().getPath());
            try (RunningServer server = start(serverConfig);
                    ManagedChannelResource channel = connect(clientConfig, "localhost", server.port())) {
                assertTransportRejected(channel.channel());
            }
        }
    }

    @Test
    void hostnameMismatchIsRejected() throws Exception {
        try (CertificateResource trusted = certificate("trusted")) {
            AppConfig config = productionConfig(trusted);
            try (RunningServer server = start(config);
                    ManagedChannelResource channel = connect(config, "127.0.0.1", server.port())) {
                assertTransportRejected(channel.channel());
            }
        }
    }

    @Test
    void newTransportConstructionReloadsRotatedCertificateFiles() throws Exception {
        try (CertificateResource first = certificate("trusted");
                CertificateResource rotated = certificate("rotated")) {
            Path certificate = tempDir.resolve("tls.crt");
            Path privateKey = tempDir.resolve("tls.key");
            Path trust = tempDir.resolve("ca.crt");
            copyIdentity(first, certificate, privateKey, trust);
            AppConfig config = productionConfig(certificate, privateKey, trust);

            try (RunningServer server = start(config);
                    ManagedChannelResource channel = connect(config, "localhost", server.port())) {
                assertDoesNotThrow(() -> invoke(channel.channel()));
            }

            copyIdentity(rotated, certificate, privateKey, trust);
            try (RunningServer server = start(config);
                    ManagedChannelResource channel = connect(config, "localhost", server.port())) {
                assertDoesNotThrow(() -> invoke(channel.channel()));
            }
        }
    }

    @Test
    void productionProfileFailsClosedWithoutKeyMaterialAndLocalProfileIsExplicit() {
        AppConfig production = new AppConfig();
        assertThrows(IllegalStateException.class, () -> GrpcTransportSecurity.from(production, Map.of()));

        AppConfig local = new AppConfig();
        local.getGrpcSecurity().setProfile("local");
        assertTrue(GrpcTransportSecurity.from(local, Map.of()).isLocalPlaintext());
    }

    private static RunningServer start(AppConfig config) throws Exception {
        Server server = GrpcTransportSecurity.from(config, Map.of())
                .serverBuilder(0)
                .addService(new ClusterServiceGrpc.ClusterServiceImplBase() {
                    @Override
                    public void getShardMap(
                            GetShardMapRequest request, StreamObserver<GetShardMapResponse> responseObserver) {
                        responseObserver.onNext(GetShardMapResponse.getDefaultInstance());
                        responseObserver.onCompleted();
                    }
                })
                .build()
                .start();
        return new RunningServer(server);
    }

    private static ManagedChannelResource connect(AppConfig config, String host, int port) {
        return new ManagedChannelResource(
                GrpcTransportSecurity.from(config, Map.of()).newChannel(host, port));
    }

    private static void invoke(ManagedChannel channel) {
        ClusterServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(3, TimeUnit.SECONDS)
                .getShardMap(GetShardMapRequest.getDefaultInstance());
    }

    private static void assertTransportRejected(ManagedChannel channel) {
        StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () -> invoke(channel));
        assertTrue(
                failure.getStatus().getCode() == io.grpc.Status.Code.UNAVAILABLE
                        || failure.getStatus().getCode() == io.grpc.Status.Code.DEADLINE_EXCEEDED,
                failure.getStatus().toString());
    }

    private static AppConfig productionConfig(CertificateResource certificate) {
        return productionConfig(
                certificate.certificate().toPath(),
                certificate.privateKey().toPath(),
                certificate.certificate().toPath());
    }

    private static AppConfig productionConfig(Path certificate, Path privateKey, Path trust) {
        AppConfig config = new AppConfig();
        config.getGrpcSecurity().setProfile("production");
        config.getGrpcSecurity().setCertificateChainPath(certificate.toString());
        config.getGrpcSecurity().setPrivateKeyPath(privateKey.toString());
        config.getGrpcSecurity().setTrustCertificateCollectionPath(trust.toString());
        return config;
    }

    private static void copyIdentity(CertificateResource source, Path certificate, Path privateKey, Path trust)
            throws Exception {
        Files.copy(source.certificate().toPath(), certificate, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(source.privateKey().toPath(), privateKey, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(source.certificate().toPath(), trust, StandardCopyOption.REPLACE_EXISTING);
    }

    private record RunningServer(Server server) implements AutoCloseable {
        private int port() {
            return server.getPort();
        }

        @Override
        public void close() throws InterruptedException {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private record ManagedChannelResource(ManagedChannel channel) implements AutoCloseable {
        @Override
        public void close() throws InterruptedException {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static CertificateResource certificate(String name) throws Exception {
        Path testClasses = Path.of(GrpcTransportSecurityTest.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        Path certificate = testClasses.resolve("grpc-tls/" + name + ".crt");
        Path privateKey = testClasses.resolve("grpc-tls/" + name + ".key");
        return new CertificateResource(certificate, privateKey);
    }

    private record CertificateResource(Path certificatePath, Path privateKeyPath) implements AutoCloseable {
        private java.io.File certificate() {
            return certificatePath.toFile();
        }

        private java.io.File privateKey() {
            return privateKeyPath.toFile();
        }

        @Override
        public void close() {}
    }
}
