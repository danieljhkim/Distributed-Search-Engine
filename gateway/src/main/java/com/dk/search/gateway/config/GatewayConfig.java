package com.dk.search.gateway.config;

import com.dk.search.common.loadbalancer.NodeClientManager;
import com.dk.search.proto.index.IndexServiceGrpc;
import com.dk.search.proto.query.QueryServiceGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class GatewayConfig {

    @Bean
    public NodeClientManager<QueryServiceGrpc.QueryServiceBlockingStub> queryNodeClientManager() {
        return NodeClientManager.forPorts(
                List.of(6000, 6001),
                "localhost",
                QueryServiceGrpc::newBlockingStub
        );
    }

    @Bean
    public NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> indexNodeClientManager() {
        return NodeClientManager.forPorts(
                List.of(5000, 5001),
                "localhost",
                IndexServiceGrpc::newBlockingStub
        );
    }
}