package com.danieljhkim.dsearch.gateway.config;

import com.danieljhkim.dsearch.common.cluster.NodeGroupManager;
import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.config.ConfigLoader;
import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.proto.cluster.ClusterServiceGrpc;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;
import com.danieljhkim.dsearch.proto.query.QueryServiceGrpc;
import java.io.IOException;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayConfig {

    @Bean
    public NodeClientManager<QueryServiceGrpc.QueryServiceBlockingStub> queryNodeClientManager(
            NodeGroupManager nodeGroupManager,
            @Qualifier("clusterNodeClientManager") NodeClientManager<ClusterServiceGrpc.ClusterServiceBlockingStub> clusterNodeClientManager) {
        nodeGroupManager.setCoordinatorManager(clusterNodeClientManager);
        return NodeClientManager.loadClientManager(
                NodeRole.NODE_ROLE_QUERY, QueryServiceGrpc::newBlockingStub, nodeGroupManager);
    }

    @Bean
    public NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> indexNodeClientManager(
            NodeGroupManager nodeGroupManager,
            @Qualifier("clusterNodeClientManager") NodeClientManager<ClusterServiceGrpc.ClusterServiceBlockingStub> clusterNodeClientManager) {
        nodeGroupManager.setCoordinatorManager(clusterNodeClientManager);
        return NodeClientManager.loadClientManager(
                NodeRole.NODE_ROLE_INDEX, IndexServiceGrpc::newBlockingStub, nodeGroupManager);
    }

    @Bean
    public NodeClientManager<ClusterServiceGrpc.ClusterServiceBlockingStub> clusterNodeClientManager(
            NodeGroupManager nodeGroupManager) {
        NodeClientManager<ClusterServiceGrpc.ClusterServiceBlockingStub> manager = NodeClientManager.loadClientManager(
                NodeRole.NODE_ROLE_COORDINATOR, ClusterServiceGrpc::newBlockingStub, nodeGroupManager);
        nodeGroupManager.setCoordinatorManager(manager);
        return manager;
    }

    @Bean
    public AppConfig appConfig() {
        try {
            return ConfigLoader.load();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load application configuration", e);
        }
    }

    @Bean
    public NodeGroupManager nodeGroupManager(AppConfig appConfig) {
        return new NodeGroupManager(appConfig);
    }

    @Bean
    public FilterRegistrationBean<RequestAdmissionFilter> requestAdmissionFilter(AppConfig appConfig) {
        FilterRegistrationBean<RequestAdmissionFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RequestAdmissionFilter(appConfig.getRequestLimits()));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        return registration;
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
