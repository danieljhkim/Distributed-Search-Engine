package com.danieljhkim.dsearch.common.grpc;

import com.danieljhkim.dsearch.common.config.AppConfig;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NegotiationType;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Secure-by-default gRPC transport construction shared by every process. */
public final class GrpcTransportSecurity {

    static final String CERTIFICATE_CHAIN_ENV = "DSEARCH_GRPC_CERTIFICATE_CHAIN";
    static final String PRIVATE_KEY_ENV = "DSEARCH_GRPC_PRIVATE_KEY";
    static final String TRUST_CERTIFICATES_ENV = "DSEARCH_GRPC_TRUST_CERTIFICATES";
    static final String PROFILE_ENV = "DSEARCH_GRPC_PROFILE";

    private final Profile profile;
    private final File certificateChain;
    private final File privateKey;
    private final File trustCertificates;

    private GrpcTransportSecurity(Profile profile, File certificateChain, File privateKey, File trustCertificates) {
        this.profile = profile;
        this.certificateChain = certificateChain;
        this.privateKey = privateKey;
        this.trustCertificates = trustCertificates;
    }

    public static GrpcTransportSecurity from(AppConfig appConfig) {
        return from(appConfig, System.getenv());
    }

    static GrpcTransportSecurity from(AppConfig appConfig, Map<String, String> environment) {
        Objects.requireNonNull(appConfig, "appConfig must not be null");
        Objects.requireNonNull(environment, "environment must not be null");
        AppConfig.GrpcSecurityConfig config =
                Objects.requireNonNull(appConfig.getGrpcSecurity(), "grpcSecurity must be configured");
        String configuredProfile = environment.get(PROFILE_ENV);
        if (configuredProfile == null || configuredProfile.isBlank()) {
            configuredProfile = config.getProfile();
        }
        Profile profile = Profile.parse(configuredProfile);
        if (profile == Profile.LOCAL) {
            return new GrpcTransportSecurity(profile, null, null, null);
        }
        return new GrpcTransportSecurity(
                profile,
                requiredFile(environment, CERTIFICATE_CHAIN_ENV, config.getCertificateChainPath()),
                requiredFile(environment, PRIVATE_KEY_ENV, config.getPrivateKeyPath()),
                requiredFile(environment, TRUST_CERTIFICATES_ENV, config.getTrustCertificateCollectionPath()));
    }

    public ManagedChannel newChannel(String host, int port) {
        NettyChannelBuilder builder = NettyChannelBuilder.forAddress(host, port);
        if (profile == Profile.LOCAL) {
            return builder.negotiationType(NegotiationType.PLAINTEXT).build();
        }
        try {
            return builder.sslContext(GrpcSslContexts.forClient()
                            .trustManager(trustCertificates)
                            .keyManager(certificateChain, privateKey)
                            .build())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to configure gRPC client mTLS", e);
        }
    }

    public NettyServerBuilder serverBuilder(int port) {
        NettyServerBuilder builder = NettyServerBuilder.forPort(port);
        if (profile == Profile.LOCAL) {
            return builder;
        }
        try {
            return builder.sslContext(GrpcSslContexts.forServer(certificateChain, privateKey)
                    .trustManager(trustCertificates)
                    .clientAuth(ClientAuth.REQUIRE)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to configure gRPC server mTLS", e);
        }
    }

    public boolean isLocalPlaintext() {
        return profile == Profile.LOCAL;
    }

    private static File requiredFile(Map<String, String> environment, String environmentName, String configuredPath) {
        String resolved = environment.get(environmentName);
        if (resolved == null || resolved.isBlank()) {
            resolved = configuredPath;
        }
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalStateException(
                    environmentName + " or its grpcSecurity configuration value is required in production");
        }
        File file = new File(resolved);
        if (!file.isFile()) {
            throw new IllegalStateException("gRPC TLS file does not exist or is not a regular file: " + file);
        }
        return file;
    }

    private enum Profile {
        PRODUCTION,
        LOCAL;

        private static Profile parse(String value) {
            if (value == null || value.isBlank()) {
                return PRODUCTION;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("grpcSecurity.profile must be 'production' or 'local': " + value, e);
            }
        }
    }
}
