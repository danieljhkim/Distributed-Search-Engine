package com.danieljhkim.dsearch.gateway.api;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.enums.HealthStatus;
import com.danieljhkim.dsearch.gateway.api.dto.ClusterHealthResponse;
import com.danieljhkim.dsearch.gateway.api.dto.ClusterHealthResponse.NodeHealthStatus;
import com.danieljhkim.dsearch.gateway.api.dto.ClusterHealthResponse.ServiceHealth;
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
    private final AppConfig appConfig;

    public HealthController(RestTemplate restTemplate, AppConfig appConfig) {
        this.restTemplate = restTemplate;
        this.appConfig = appConfig;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", HealthStatus.UP.name(),
                "service", "gateway",
                "timestamp", Instant.now().toString());
    }

    @GetMapping("/cluster/health")
    public ResponseEntity<ClusterHealthResponse> clusterHealth() {
        ServiceHealth gatewayHealth = new ServiceHealth(HealthStatus.UP.name());

        List<NodeHealthStatus> indexNodeHealth = checkGroup("index-node", appConfig.getIndexNodes());
        List<NodeHealthStatus> queryNodeHealth = checkGroup("query-node", appConfig.getQueryNodes());
        List<NodeHealthStatus> coordinatorNodeHealth = checkGroup("coordinator", appConfig.getCoordinatorNodes());
        boolean allNodesUp = indexNodeHealth.stream()
                        .allMatch(h -> HealthStatus.UP.name().equals(h.getStatus()))
                && queryNodeHealth.stream().allMatch(h -> HealthStatus.UP.name().equals(h.getStatus()))
                && coordinatorNodeHealth.stream()
                        .allMatch(h -> HealthStatus.UP.name().equals(h.getStatus()));

        String overallStatus = allNodesUp ? HealthStatus.UP.name() : HealthStatus.DEGRADED.name();
        HttpStatus httpStatus = allNodesUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        ClusterHealthResponse body = new ClusterHealthResponse(
                overallStatus, gatewayHealth, indexNodeHealth, queryNodeHealth, coordinatorNodeHealth, Instant.now());

        return new ResponseEntity<>(body, httpStatus);
    }

    private List<NodeHealthStatus> checkGroup(String serviceName, AppConfig.NodeGroupConfig groupConfig) {
        List<NodeHealthStatus> results = new ArrayList<>();
        if (groupConfig == null || groupConfig.getNodes() == null) {
            return results;
        }

        groupConfig.getNodes().forEach(node -> {
            String id = node.getId();
            String host = node.getHost();
            int grpcPort = node.getPort();
            int healthPort = node.getHealthPort();
            String url = "http://" + host + ":" + healthPort + "/health";
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

            NodeHealthStatus nodeHealth = new NodeHealthStatus(id, host, grpcPort, healthPort, status.name(), error);
            results.add(nodeHealth);
        });

        return results;
    }
}
