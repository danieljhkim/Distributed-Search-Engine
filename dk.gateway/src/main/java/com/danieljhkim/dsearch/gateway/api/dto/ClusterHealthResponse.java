package com.danieljhkim.dsearch.gateway.api.dto;

import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ClusterHealthResponse {

    private String status; // UP / DEGRADED / DOWN
    private ServiceHealth gateway;
    private List<NodeHealthStatus> indexNodes;
    private List<NodeHealthStatus> queryNodes;
    private List<NodeHealthStatus> coordinatorNodes;
    private ReplicationHealth replication;
    private Instant timestamp;

    public ClusterHealthResponse(
            String status,
            ServiceHealth gateway,
            List<NodeHealthStatus> indexNodes,
            List<NodeHealthStatus> queryNodes,
            List<NodeHealthStatus> coordinatorNodes,
            Instant timestamp) {
        this(status, gateway, indexNodes, queryNodes, coordinatorNodes, null, timestamp);
    }

    public ClusterHealthResponse(
            String status,
            ServiceHealth gateway,
            List<NodeHealthStatus> indexNodes,
            List<NodeHealthStatus> queryNodes,
            List<NodeHealthStatus> coordinatorNodes,
            ReplicationHealth replication,
            Instant timestamp) {
        this.status = status;
        this.gateway = gateway;
        this.indexNodes = indexNodes;
        this.queryNodes = queryNodes;
        this.coordinatorNodes = coordinatorNodes;
        this.replication = replication;
        this.timestamp = timestamp;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReplicationHealth {
        private long generation;
        private int replicationFactor;
        private String durabilityPolicy;
        private String readConsistency;
        private int logicalShards;
        private int underReplicatedShards;
        private int failedOverShards;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServiceHealth {
        private String status;
        private String reason;

        public ServiceHealth(String status) {
            this(status, null);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NodeHealthStatus {
        private String id;
        private String host;
        private int grpcPort;
        private int healthPort;
        private String status; // UP / DOWN
        private String error;
    }
}
