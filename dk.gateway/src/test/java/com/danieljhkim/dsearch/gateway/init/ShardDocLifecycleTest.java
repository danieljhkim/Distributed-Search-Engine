package com.danieljhkim.dsearch.gateway.init;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.common.shard.ShardStateStore;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;
import java.io.InterruptedIOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class ShardDocLifecycleTest {

    @Test
    void initializerAppliesAValidSnapshot() throws Exception {
        ShardStateStore store = mock(ShardStateStore.class);
        NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> manager = mock(NodeClientManager.class);
        ShardStateStore.ShardDocSnapshot snapshot = new ShardStateStore.ShardDocSnapshot();
        when(store.load()).thenReturn(snapshot);

        new ShardDocInitConfig(store, manager).shardDocInitializer().run(new DefaultApplicationArguments());

        verify(manager).applySnapshot(snapshot);
    }

    @Test
    void initializerStartsFreshWhenSnapshotIsMissingOrMalformed() throws Exception {
        ShardStateStore store = mock(ShardStateStore.class);
        NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> manager = mock(NodeClientManager.class);
        when(store.load()).thenThrow(new InterruptedIOException("snapshot read interrupted"));

        new ShardDocInitConfig(store, manager).shardDocInitializer().run(new DefaultApplicationArguments());

        verify(manager, never()).applySnapshot(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void persistenceSavesTheCurrentSnapshot() throws Exception {
        ShardStateStore store = mock(ShardStateStore.class);
        NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> manager = mock(NodeClientManager.class);
        ShardStateStore.ShardDocSnapshot snapshot = new ShardStateStore.ShardDocSnapshot();
        when(manager.snapshotShardDocCounts()).thenReturn(snapshot);

        new ShardDocPersistence(store, manager).persistShardDocCounts();

        verify(store).save(snapshot);
    }

    @Test
    void persistenceContainsInterruptedSnapshotWrites() throws Exception {
        ShardStateStore store = mock(ShardStateStore.class);
        NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> manager = mock(NodeClientManager.class);
        ShardStateStore.ShardDocSnapshot snapshot = new ShardStateStore.ShardDocSnapshot();
        when(manager.snapshotShardDocCounts()).thenReturn(snapshot);
        doThrow(new InterruptedIOException("snapshot write interrupted"))
                .when(store)
                .save(snapshot);

        new ShardDocPersistence(store, manager).persistShardDocCounts();

        verify(store).save(snapshot);
    }
}
