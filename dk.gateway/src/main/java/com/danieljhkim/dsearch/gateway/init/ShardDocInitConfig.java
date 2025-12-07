package com.danieljhkim.dsearch.gateway.init;

import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.common.shard.ShardStateStore;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class ShardDocInitConfig {

    private final ShardStateStore shardStateStore;
    private final NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> indexNodeClientManager;

    @Bean
    public ApplicationRunner shardDocInitializer() {
        return args -> {
            try {
                var snapshot = shardStateStore.load();
                indexNodeClientManager.applySnapshot(snapshot);
                log.info("Restored shard doc counts from disk");
            } catch (Exception e) {
                log.warn("No shard doc snapshot found or failed to load; starting fresh", e);
            }
        };
    }
}