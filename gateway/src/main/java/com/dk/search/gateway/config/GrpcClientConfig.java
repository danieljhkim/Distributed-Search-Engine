package com.dk.search.gateway.config;

import com.dk.search.common.config.GrpcChannelConfig;
import com.dk.search.proto.index.IndexServiceGrpc;
import com.dk.search.proto.query.QueryServiceGrpc;
import io.grpc.ManagedChannel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcClientConfig {

    @Bean
    public ManagedChannel indexNodeChannel() {
        return GrpcChannelConfig.getIndexChannel();
    }

    @Bean
    public ManagedChannel queryNodeChannel() {
        return GrpcChannelConfig.getQueryChannel();
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