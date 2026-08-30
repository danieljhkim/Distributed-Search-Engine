package com.danieljhkim.dsearch.gateway.api.dto;

import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClusterHealthResponse {

    private String status; // UP / DEGRADED / DOWN
    private ServiceHealth gateway;
    private List<NodeHealthStatus> indexNodes;
    private List<NodeHealthStatus> queryNodes;
    private List<NodeHealthStatus> coordinatorNodes;
    private Instant timestamp;

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
