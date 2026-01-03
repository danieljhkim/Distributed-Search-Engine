package com.danieljhkim.dsearch.gateway.init;

import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.common.shard.ShardStateStore;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class ShardDocPersistence {

    private final ShardStateStore shardStateStore;
    private final NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> indexNodeClientManager;

    @Scheduled(fixedDelayString = "${shards.snapshot.interval-ms:5000}")
    public void persistShardDocCounts() {
        try {
            var snapshot = indexNodeClientManager.snapshotShardDocCounts();
            shardStateStore.save(snapshot);
            log.debug("Persisted shard doc counts snapshot");
        } catch (Exception e) {
            log.warn("Failed to persist shard doc counts snapshot", e);
        }
    }
}
