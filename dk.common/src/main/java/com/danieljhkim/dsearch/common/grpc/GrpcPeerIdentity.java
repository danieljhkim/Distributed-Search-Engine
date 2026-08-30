package com.danieljhkim.dsearch.common.grpc;

import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import java.net.URI;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/** Authenticated service identity carried in a SPIFFE URI SAN. */
public record GrpcPeerIdentity(IdentityRole role, String name) {

    public GrpcPeerIdentity {
        Objects.requireNonNull(role, "role must not be null");
        if (name == null || name.isBlank() || name.contains("/")) {
            throw new IllegalArgumentException("identity name must be a non-empty path segment");
        }
    }

    public static GrpcPeerIdentity admin(String name) {
        return new GrpcPeerIdentity(IdentityRole.ADMIN, name);
    }

    public static GrpcPeerIdentity node(NodeRole role, String nodeId) {
        return new GrpcPeerIdentity(IdentityRole.fromNodeRole(role), nodeId);
    }

    static GrpcPeerIdentity fromSession(SSLSession session) throws SSLPeerUnverifiedException {
        if (session == null) {
            throw new SSLPeerUnverifiedException("TLS peer session is missing");
        }
        Certificate[] certificates = session.getPeerCertificates();
        if (certificates.length == 0 || !(certificates[0] instanceof X509Certificate peer)) {
            throw new SSLPeerUnverifiedException("TLS peer did not provide an X.509 certificate");
        }
        return fromCertificate(peer);
    }

    static GrpcPeerIdentity fromCertificate(X509Certificate certificate) throws SSLPeerUnverifiedException {
        try {
            Collection<List<?>> names = certificate.getSubjectAlternativeNames();
            if (names == null) {
                throw new SSLPeerUnverifiedException("TLS peer certificate has no subject alternative names");
            }
            GrpcPeerIdentity identity = null;
            for (List<?> name : names) {
                if (name.size() >= 2 && Integer.valueOf(6).equals(name.get(0)) && name.get(1) instanceof String uri) {
                    GrpcPeerIdentity candidate = parseUri(uri);
                    if (candidate != null) {
                        if (identity != null) {
                            throw new SSLPeerUnverifiedException(
                                    "TLS peer certificate has multiple dsearch identity URI SANs");
                        }
                        identity = candidate;
                    }
                }
            }
            if (identity == null) {
                throw new SSLPeerUnverifiedException("TLS peer certificate has no dsearch SPIFFE identity URI SAN");
            }
            return identity;
        } catch (SSLPeerUnverifiedException e) {
            throw e;
        } catch (Exception e) {
            SSLPeerUnverifiedException failure =
                    new SSLPeerUnverifiedException("Failed to parse TLS peer identity: " + e.getMessage());
            failure.initCause(e);
            throw failure;
        }
    }

    static GrpcPeerIdentity parseUri(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!"spiffe".equals(uri.getScheme())
                || !"dsearch".equals(uri.getHost())
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            return null;
        }
        String[] segments = uri.getPath().split("/", -1);
        if (segments.length == 3 && "admin".equals(segments[1])) {
            return admin(segments[2]);
        }
        if (segments.length != 4 || !"node".equals(segments[1])) {
            return null;
        }
        return new GrpcPeerIdentity(IdentityRole.parse(segments[2]), segments[3]);
    }

    public boolean authorizes(NodeRole requestedRole, String requestedNodeId) {
        return role == IdentityRole.ADMIN || (role.nodeRole == requestedRole && name.equals(requestedNodeId));
    }

    public enum IdentityRole {
        INDEX(NodeRole.NODE_ROLE_INDEX),
        QUERY(NodeRole.NODE_ROLE_QUERY),
        COORDINATOR(NodeRole.NODE_ROLE_COORDINATOR),
        ADMIN(null);

        private final NodeRole nodeRole;

        IdentityRole(NodeRole nodeRole) {
            this.nodeRole = nodeRole;
        }

        private static IdentityRole parse(String value) {
            return switch (value) {
                case "index" -> INDEX;
                case "query" -> QUERY;
                case "coordinator" -> COORDINATOR;
                default -> throw new IllegalArgumentException("unsupported dsearch identity role: " + value);
            };
        }

        private static IdentityRole fromNodeRole(NodeRole role) {
            return switch (role) {
                case NODE_ROLE_INDEX -> INDEX;
                case NODE_ROLE_QUERY -> QUERY;
                case NODE_ROLE_COORDINATOR -> COORDINATOR;
                default -> throw new IllegalArgumentException("unsupported node role: " + role);
            };
        }
    }
}
