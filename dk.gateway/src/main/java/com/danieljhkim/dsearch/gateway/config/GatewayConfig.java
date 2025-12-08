package com.danieljhkim.dsearch.gateway.config;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.config.ConfigLoader;
import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.proto.cluster.ClusterServiceGrpc;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;
import com.danieljhkim.dsearch.proto.query.QueryServiceGrpc;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class GatewayConfig {

    @Bean
    public NodeClientManager<QueryServiceGrpc.QueryServiceBlockingStub> queryNodeClientManager() {
        return NodeClientManager.loadClientManager(
                NodeRole.NODE_ROLE_QUERY,
                QueryServiceGrpc::newBlockingStub
        );
    }

    @Bean
    public NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> indexNodeClientManager() {
        return NodeClientManager.loadClientManager(
                NodeRole.NODE_ROLE_INDEX,
                IndexServiceGrpc::newBlockingStub
        );
    }

    @Lazy
    @Bean
    public NodeClientManager<ClusterServiceGrpc.ClusterServiceBlockingStub> clusterNodeClientManager() {
        return NodeClientManager.loadClientManager(
                NodeRole.NODE_ROLE_COORDINATOR,
                ClusterServiceGrpc::newBlockingStub
        );
    }

    @Bean
    public AppConfig appConfig() {
        try {
            return ConfigLoader.load("app-config.yaml");
        } catch (Exception e) {
            throw new RuntimeException("Failed to load application configuration", e);
        }
    }

    @Bean
    @ConfigurationProperties(prefix = "server")
    public ServerProperties serverProperties() {
        return new ServerProperties();
    }

    @Getter
    @Setter
    public static class ServerProperties {
        private String host;
        private int port;
    }

}