package com.danieljhkim.dsearch.common.config;

import com.danieljhkim.dsearch.common.enums.FieldType;
import com.danieljhkim.dsearch.common.enums.RoutingStrategy;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class AppConfig {
    private ServiceDiscoveryConfig serviceDiscovery;
    private GrpcSecurityConfig grpcSecurity = new GrpcSecurityConfig();
    private NodeGroupConfig indexNodes;
    private NodeGroupConfig queryNodes;
    private NodeGroupConfig coordinatorNodes;
    private NodeGroupConfig gatewayNodes;
    private IndexingConfig indexing;
    private MlConfig ml;
    private RequestLimitsConfig requestLimits;
    private PaginationConfig pagination = new PaginationConfig();
    private ReplicaRepairConfig replicaRepair = new ReplicaRepairConfig();
    private List<FieldConfig> fieldConfigs;

    @Setter
    @Getter
    public static class GrpcSecurityConfig {
        /**
         * {@code production} requires mutually authenticated TLS. {@code local} is the only
         * plaintext profile and must be selected explicitly by a local-only launcher or
         * configuration.
         */
        private String profile = "production";

        private String certificateChainPath;
        private String privateKeyPath;
        private String trustCertificateCollectionPath;
    }

    @Override
    public String toString() {
        return "AppConfig{" + "indexNodes=" + indexNodes + ", queryNodes=" + queryNodes + ", ml=" + ml + '}';
    }

    @Setter
    @Getter
    public static class ServiceDiscoveryConfig {
        private boolean enabled = false;
        private int refreshIntervalSeconds = 30;
        private int nodeExpirySeconds = 30;
        private int maxStalenessSeconds = 30;
        private int heartbeatIntervalSeconds = 10;
        private int registrationInitialBackoffMillis = 250;
        private int registrationMaxBackoffSeconds = 10;
        private int membershipRpcDeadlineMillis = 2000;
        private int shutdownDeregisterTimeoutMillis = 1000;
        private String coordinatorStateFile;
    }

    @Setter
    @Getter
    public static class NodeGroupConfig {
        private List<NodeConfig> nodes;
        private RoutingStrategy routingStrategy;
        private String componentLabel;
        private int replicationFactor = 1;
        private String durabilityPolicy = "all";
        private String readConsistency = "acknowledged";

        @Override
        public String toString() {
            return "NodeGroupConfig{" + "nodes="
                    + nodes + ", routingStrategy="
                    + routingStrategy + ", componentLabel='"
                    + componentLabel + "', replicationFactor="
                    + replicationFactor + ", durabilityPolicy='"
                    + durabilityPolicy + "', readConsistency='"
                    + readConsistency + "'}";
        }
    }

    @Setter
    @Getter
    public static class NodeConfig {
        private String id;
        private String host;
        private int port;
        private int healthPort;
        private String role;

        @Override
        public String toString() {
            return "NodeConfig{" + "id='"
                    + id + '\'' + ", host='"
                    + host + '\'' + ", port="
                    + port + '\'' + ", healthPort="
                    + healthPort + '}';
        }
    }

    @Setter
    @Getter
    public static class MlConfig {
        private ModelsConfig models;

        @Override
        public String toString() {
            return "MlConfig{" + "models=" + models + '}';
        }
    }

    @Setter
    @Getter
    public static class ModelsConfig {
        private TextEmbeddingConfig textEmbedding;

        @Override
        public String toString() {
            return "ModelsConfig{" + "textEmbedding=" + textEmbedding + '}';
        }
    }

    @Setter
    @Getter
    public static class TextEmbeddingConfig {
        private String url;
        private String engine;
        private int predictorPoolSize = 1;
        private boolean predictorPerCall = false;
        /** Output vector length. 0 means discover from the first embedding. */
        private int dimension = 0;

        @Override
        public String toString() {
            return "TextEmbeddingConfig{" + "url='"
                    + url + '\'' + ", engine='"
                    + engine + '\'' + ", predictorPoolSize="
                    + predictorPoolSize + ", predictorPerCall="
                    + predictorPerCall + ", dimension="
                    + dimension + '}';
        }
    }

    @Setter
    @Getter
    public static class IndexingConfig {
        private int maxBufferedOpsPerShard = 100;
        private int maxFlushIntervalSeconds = 5;
        /** Minimum usable bytes required on the Lucene volume before this node is ready. */
        private long minimumFreeDiskBytes = 104857600L;
        /** Analyzer name persisted as part of the index schema contract. */
        private String analyzer = "standard";

        @Override
        public String toString() {
            return "IndexingConfig{" + "maxBufferedOpsPerShard="
                    + maxBufferedOpsPerShard + ", maxFlushIntervalSeconds="
                    + maxFlushIntervalSeconds + ", minimumFreeDiskBytes="
                    + minimumFreeDiskBytes + ", analyzer='"
                    + analyzer + '\'' + '}';
        }
    }

    @Setter
    @Getter
    public static class RequestLimitsConfig {
        private int requestTimeoutMillis = 3000;
        private int maxSize = 1000;
        private int maxResultWindow = 10000;
        private int maxQueryLength = 1024;
        private int maxHttpBodyBytes = 1048576;
        private int maxGrpcInboundBytes = 2097152;
        private int maxDocumentIdBytes = 512;
        private int maxFieldsPerDocument = 100;
        private int maxFieldValueBytes = 65536;
        private int maxIndexPayloadBytes = 1048576;
        private int maxBulkItems = 100;
        private long maxBulkEmbeddingBytes = 524288;
        private int maxFilterClauses = 100;
        private int maxFacetCount = 20;
        private int maxFacetDepth = 3;
        /** Maximum UTF-8 bytes of sample text accepted by an analyzer token preview. */
        private int maxAnalyzeTextBytes = 8192;
        /** Maximum tokens returned by an analyzer token preview; the stream is truncated beyond it. */
        private int maxAnalyzeTokens = 256;
        /**
         * Maximum deterministic upper bound for all facet buckets expanded across a recursive
         * request tree. Each level contributes the product of its size and every ancestor size.
         */
        private long maxFacetExpandedBuckets = 10000L;

        private int maxConcurrentHttpRequests = 128;
        private int maxConcurrentFanoutCalls = 64;
        private int retryAfterMillis = 100;

        @Override
        public String toString() {
            return "RequestLimitsConfig{" + "requestTimeoutMillis=" + requestTimeoutMillis + ", maxSize=" + maxSize
                    + ", maxResultWindow=" + maxResultWindow + ", maxQueryLength=" + maxQueryLength
                    + ", maxHttpBodyBytes=" + maxHttpBodyBytes + ", maxGrpcInboundBytes=" + maxGrpcInboundBytes
                    + ", maxDocumentIdBytes=" + maxDocumentIdBytes + ", maxFieldsPerDocument="
                    + maxFieldsPerDocument + ", maxFieldValueBytes=" + maxFieldValueBytes
                    + ", maxIndexPayloadBytes=" + maxIndexPayloadBytes + ", maxBulkItems=" + maxBulkItems
                    + ", maxBulkEmbeddingBytes=" + maxBulkEmbeddingBytes + ", maxFilterClauses=" + maxFilterClauses
                    + ", maxFacetCount=" + maxFacetCount + ", maxFacetDepth=" + maxFacetDepth
                    + ", maxFacetExpandedBuckets=" + maxFacetExpandedBuckets
                    + ", maxAnalyzeTextBytes=" + maxAnalyzeTextBytes + ", maxAnalyzeTokens=" + maxAnalyzeTokens
                    + ", maxConcurrentHttpRequests=" + maxConcurrentHttpRequests + ", maxConcurrentFanoutCalls="
                    + maxConcurrentFanoutCalls + ", retryAfterMillis=" + retryAfterMillis + '}';
        }
    }

    @Setter
    @Getter
    public static class PaginationConfig {
        /**
         * Shared HMAC key for opaque pagination cursors. Every query node must use the same value:
         * a gateway load-balances pages of one traversal across nodes, so a per-node key makes page
         * two fail signature verification. Blank generates a process-local key, which is fine for a
         * single-node development cluster and logs a warning everywhere else.
         */
        private String cursorSigningKey = "";

        /** Upper bound on sort components in one request, before the id tie-breaker is appended. */
        private int maxSortFields = 8;

        @Override
        public String toString() {
            return "PaginationConfig{" + "cursorSigningKey="
                    + (cursorSigningKey == null || cursorSigningKey.isBlank() ? "<generated>" : "<configured>")
                    + ", maxSortFields=" + maxSortFields + '}';
        }
    }

    @Setter
    @Getter
    public static class ReplicaRepairConfig {
        private boolean enabled = true;
        private int intervalSeconds = 15;
        private int rpcDeadlineMillis = 5000;
        private int chunkBytes = 262144;
        private long maxSnapshotBytes = 1073741824L;
        private long bandwidthBytesPerSecond = 10485760L;
        private int maxConcurrentRepairs = 1;
    }

    /**
     * Configuration for a document field specifying its type and capabilities.
     */
    @Setter
    @Getter
    public static class FieldConfig {
        private String name;
        private FieldType type = FieldType.STRING;
        private boolean filterable = false;
        private boolean sortable = false;
        private boolean facetable = false;
        private boolean highlightable = false;
        private String analyzer = "standard";

        @Override
        public String toString() {
            return "FieldConfig{" + "name='"
                    + name + '\'' + ", type="
                    + type + ", filterable="
                    + filterable + ", sortable="
                    + sortable + ", facetable="
                    + facetable + ", highlightable="
                    + highlightable + ", analyzer='"
                    + analyzer + '\'' + '}';
        }
    }
}
