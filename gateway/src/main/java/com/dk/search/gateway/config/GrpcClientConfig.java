package com.dk.search.gateway.config;

import com.dk.search.proto.index.IndexServiceGrpc;
import com.dk.search.proto.query.QueryServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcClientConfig {

    @Bean
    public ManagedChannel indexNodeChannel() {
        String host = System.getenv().getOrDefault("INDEX_NODE_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("INDEX_NODE_PORT", "5000"));

        return ManagedChannelBuilder
                .forAddress(host, port)
                .usePlaintext()
                .build();
    }

    @Bean
    public ManagedChannel queryNodeChannel() {
        String host = System.getenv().getOrDefault("QUERY_NODE_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("QUERY_NODE_PORT", "6000"));

        return ManagedChannelBuilder
                .forAddress(host, port)
                .usePlaintext()
                .build();
    }

    @Bean
    public IndexServiceGrpc.IndexServiceBlockingStub indexServiceStub(
            @Qualifier("indexNodeChannel") ManagedChannel indexNodeChannel
    ) {
        return IndexServiceGrpc.newBlockingStub(indexNodeChannel);
    }

    @Bean
    public QueryServiceGrpc.QueryServiceBlockingStub queryServiceStub(
            @Qualifier("queryNodeChannel") ManagedChannel queryNodeChannel
    ) {
        return QueryServiceGrpc.newBlockingStub(queryNodeChannel);
    }
}