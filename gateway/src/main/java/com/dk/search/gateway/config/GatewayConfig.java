package com.dk.search.gateway.config;

import com.dk.search.common.config.AppConfig;
import com.dk.search.common.config.ConfigLoader;
import com.dk.search.common.grpc.NodeClientManager;
import com.dk.search.proto.index.IndexServiceGrpc;
import com.dk.search.proto.query.QueryServiceGrpc;
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