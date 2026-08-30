package com.danieljhkim.dsearch.gateway.api;

import com.danieljhkim.dsearch.common.cluster.NodeGroup;
import com.danieljhkim.dsearch.common.cluster.NodeGroupManager;
import com.danieljhkim.dsearch.common.enums.HealthStatus;
import com.danieljhkim.dsearch.gateway.api.dto.ClusterHealthResponse;
import com.danieljhkim.dsearch.gateway.api.dto.ClusterHealthResponse.NodeHealthStatus;
import com.danieljhkim.dsearch.gateway.api.dto.ClusterHealthResponse.ServiceHealth;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
public class HealthController {

    private final RestTemplate restTemplate;
    private final NodeGroupManager nodeGroupManager;

    public HealthController(RestTemplate restTemplate, NodeGroupManager nodeGroupManager) {
        this.restTemplate = restTemplate;
        this.nodeGroupManager = nodeGroupManager;
    }

    /** Cheap process liveness; it never depends on coordinator or downstream availability. */
    @GetMapping({"/health", "/livez"})
    public Map<String, Object> health() {
        return Map.of(
                "status", HealthStatus.UP.name(),
                "service", "gateway",
                "timestamp", Instant.now().toString());
    }

    /**
     * Dependency-aware gateway admission. Returns 200 only while authoritative topology and all
     * required downstream nodes are ready; failures return 503 with an actionable reason.
     */
    @GetMapping("/readyz")
    public ResponseEntity<Map<String, Object>> readiness() {
        HealthAssessment assessment = assessCluster();
        if (assessment.ready()) {
            return ResponseEntity.ok(Map.of(
                    "status", HealthStatus.UP.name(),
                    "service", "gateway",
                    "timestamp", Instant.now().toString()));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "status", HealthStatus.DOWN.name(),
                        "service", "gateway",
                        "reason", assessment.reason(),
                        "timestamp", Instant.now().toString()));
    }

    @GetMapping("/cluster/health")
    public ResponseEntity<ClusterHealthResponse> clusterHealth() {
        HealthAssessment assessment = assessCluster();
        return new ResponseEntity<>(
                assessment.response(), assessment.ready() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE);
    }

    private HealthAssessment assessCluster() {
        try {
            NodeGroup indexNodes = nodeGroupManager.getNodeGroup(NodeRole.NODE_ROLE_INDEX);
            NodeGroup queryNodes = nodeGroupManager.getNodeGroup(NodeRole.NODE_ROLE_QUERY);
            NodeGroup coordinatorNodes = nodeGroupManager.getNodeGroup(NodeRole.NODE_ROLE_COORDINATOR);

            List<NodeHealthStatus> indexNodeHealth = checkGroup(indexNodes);
            List<NodeHealthStatus> queryNodeHealth = checkGroup(queryNodes);
            List<NodeHealthStatus> coordinatorNodeHealth = checkGroup(coordinatorNodes);
            String missingTopology = missingTopology(indexNodeHealth, queryNodeHealth, coordinatorNodeHealth);
            boolean allNodesReady = missingTopology == null
                    && indexNodeHealth.stream().allMatch(this::isUp)
                    && queryNodeHealth.stream().allMatch(this::isUp)
                    && coordinatorNodeHealth.stream().allMatch(this::isUp);
            String reason = allNodesReady ? null : missingTopology != null ? missingTopology : "downstream_not_ready";
            String status = allNodesReady ? HealthStatus.UP.name() : HealthStatus.DEGRADED.name();
            ClusterHealthResponse response = new ClusterHealthResponse(
                    status,
                    new ServiceHealth(HealthStatus.UP.name(), reason),
                    indexNodeHealth,
                    queryNodeHealth,
                    coordinatorNodeHealth,
                    Instant.now());
            return new HealthAssessment(allNodesReady, reason, response);
        } catch (RuntimeException e) {
            String reason = "authoritative_topology_unavailable:" + e.getClass().getSimpleName();
            ClusterHealthResponse response = new ClusterHealthResponse(
                    HealthStatus.DOWN.name(),
                    new ServiceHealth(HealthStatus.DOWN.name(), reason),
                    List.of(),
                    List.of(),
                    List.of(),
                    Instant.now());
            return new HealthAssessment(false, reason, response);
        }
    }

    private List<NodeHealthStatus> checkGroup(NodeGroup group) {
        List<NodeHealthStatus> results = new ArrayList<>();
        for (NodeGroup.NodeInfo node : group.getAllNodes()) {
            String url = "http://" + node.getHost() + ":" + node.getHealthPort() + "/readyz";
            HealthStatus status = HealthStatus.DOWN;
            String error = null;
            try {
                var response = restTemplate.getForEntity(url, String.class);
                if (response.getStatusCode().is2xxSuccessful()) {
                    status = HealthStatus.UP;
                } else {
                    status = HealthStatus.DOWN;
                    error = "HTTP " + response.getStatusCode().value();
                }
            } catch (Exception ex) {
                status = HealthStatus.DOWN;
                error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            }
            results.add(new NodeHealthStatus(
                    node.getNodeId(), node.getHost(), node.getPort(), node.getHealthPort(), status.name(), error));
        }
        return results;
    }

    private boolean isUp(NodeHealthStatus health) {
        return HealthStatus.UP.name().equals(health.getStatus());
    }

    private static String missingTopology(
            List<NodeHealthStatus> indexNodes,
            List<NodeHealthStatus> queryNodes,
            List<NodeHealthStatus> coordinatorNodes) {
        if (indexNodes.isEmpty()) {
            return "authoritative_index_topology_empty";
        }
        if (queryNodes.isEmpty()) {
            return "authoritative_query_topology_empty";
        }
        if (coordinatorNodes.isEmpty()) {
            return "authoritative_coordinator_topology_empty";
        }
        return null;
    }

    private record HealthAssessment(boolean ready, String reason, ClusterHealthResponse response) {}
}
