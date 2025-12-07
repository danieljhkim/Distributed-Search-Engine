package com.danieljhkim.dsearch.gateway.api;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.gateway.api.dto.ClusterHealthResponse;
import com.danieljhkim.dsearch.gateway.api.dto.ClusterHealthResponse.NodeHealthStatus;
import com.danieljhkim.dsearch.gateway.api.dto.ClusterHealthResponse.ServiceHealth;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
                "status", "UP",
                "service", "gateway",
                "timestamp", Instant.now().toString()
        );
    }

    @GetMapping("/cluster/health")
    public ResponseEntity<ClusterHealthResponse> clusterHealth() {
        ServiceHealth gatewayHealth = new ServiceHealth("UP");

        List<NodeHealthStatus> indexNodeHealth = checkGroup("index-node", appConfig.getIndexNodes());
        List<NodeHealthStatus> queryNodeHealth = checkGroup("query-node", appConfig.getQueryNodes());
        boolean allNodesUp = indexNodeHealth.stream().allMatch(h -> "UP".equals(h.getStatus()))
                && queryNodeHealth.stream().allMatch(h -> "UP".equals(h.getStatus()));

        String overallStatus = allNodesUp ? "UP" : "DEGRADED";
        HttpStatus httpStatus = allNodesUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        ClusterHealthResponse body = new ClusterHealthResponse(
                overallStatus,
                gatewayHealth,
                indexNodeHealth,
                queryNodeHealth,
                Instant.now()
        );

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
            String status = "DOWN";
            String error = null;

            try {
                var response = restTemplate.getForEntity(url, String.class);
                if (response.getStatusCode().is2xxSuccessful()) {
                    status = "UP";
                } else {
                    status = "DOWN";
                    error = "HTTP " + response.getStatusCode().value();
                }
            } catch (Exception ex) {
                status = "DOWN";
                error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            }

            NodeHealthStatus nodeHealth = new NodeHealthStatus(
                    id,
                    host,
                    grpcPort,
                    healthPort,
                    status,
                    error
            );
            results.add(nodeHealth);
        });

        return results;
    }
}