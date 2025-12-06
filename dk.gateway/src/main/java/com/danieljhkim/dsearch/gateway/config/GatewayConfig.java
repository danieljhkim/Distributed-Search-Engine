package com.danieljhkim.dsearch.gateway.config;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.config.ConfigLoader;
import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;
import com.danieljhkim.dsearch.proto.query.QueryServiceGrpc;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayConfig {

    @Bean
    public NodeClientManager<QueryServiceGrpc.QueryServiceBlockingStub> queryNodeClientManager(AppConfig appConfig) {
        return NodeClientManager.forPorts(
                appConfig.getQueryNode().getClient().getPorts(),
                appConfig.getQueryNode().getClient().getHost(),
                QueryServiceGrpc::newBlockingStub
        );
    }

    @Bean
    public NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> indexNodeClientManager(AppConfig appConfig) {
        return NodeClientManager.forPorts(
                appConfig.getIndexNode().getClient().getPorts(),
                appConfig.getIndexNode().getClient().getHost(),
                IndexServiceGrpc::newBlockingStub
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