package com.dk.search.gateway.config;

import com.dk.search.common.loadbalancer.NodeClientManager;
import com.dk.search.proto.index.IndexServiceGrpc;
import com.dk.search.proto.query.QueryServiceGrpc;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayConfig {

    @Bean
    public NodeClientManager<QueryServiceGrpc.QueryServiceBlockingStub> queryNodeClientManager(
            @Qualifier("queryNodeClientProperties") NodePropertiesConfig.NodeProperties queryProperties) {
        return NodeClientManager.forPorts(
                queryProperties.getPorts().stream().toList(),
                queryProperties.getHost(),
                QueryServiceGrpc::newBlockingStub
        );
    }

    @Bean
    public NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> indexNodeClientManager(
            @Qualifier("indexNodeClientProperties") NodePropertiesConfig.NodeProperties indexProperties) {
        return NodeClientManager.forPorts(
                indexProperties.getPorts().stream().toList(),
                indexProperties.getHost(),
                IndexServiceGrpc::newBlockingStub
        );
    }
}