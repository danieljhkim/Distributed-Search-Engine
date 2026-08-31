package com.danieljhkim.dsearch.common.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexAliasStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void swapIsAtomicAndRollbackRestoresThePreviousTarget() throws Exception {
        IndexAliasStore store = new IndexAliasStore(tempDir);
        store.load();
        store.putAlias("catalog", "catalog_1", null, 1);

        IndexAlias swapped = store.swap("catalog", "catalog_2");
        assertEquals("catalog_2", swapped.getIndexName());
        assertEquals("catalog_1", swapped.getPreviousIndexName());
        assertTrue(Files.exists(tempDir.resolve(IndexAliasStore.ALIAS_FILE_NAME)));

        IndexAliasStore reopened = new IndexAliasStore(tempDir);
        reopened.load();
        assertEquals("catalog_2", reopened.resolve("catalog"));

        IndexAlias rolledBack = reopened.rollback("catalog");
        assertEquals("catalog_1", rolledBack.getIndexName());
        assertEquals("catalog_2", rolledBack.getPreviousIndexName());
        assertEquals("catalog_1", reopened.resolve("catalog"));
    }

    @Test
    void concurrentReadersObserveACompleteAliasSnapshot() throws Exception {
        IndexAliasStore store = new IndexAliasStore(tempDir);
        store.load();
        store.putAlias("catalog", "catalog_1", null, 1);

        int readers = 8;
        CyclicBarrier start = new CyclicBarrier(readers + 1);
        CountDownLatch done = new CountDownLatch(readers);
        ExecutorService executor = Executors.newFixedThreadPool(readers);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < readers; i++) {
                results.add(executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    String resolved = store.resolve("catalog");
                    done.countDown();
                    return "catalog_1".equals(resolved) || "catalog_2".equals(resolved);
                }));
            }
            start.await(5, TimeUnit.SECONDS);
            store.swap("catalog", "catalog_2");
            assertTrue(done.await(5, TimeUnit.SECONDS));
            for (Future<Boolean> result : results) {
                assertTrue(result.get(5, TimeUnit.SECONDS));
            }
            assertEquals("catalog_2", store.resolve("catalog"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rollbackWithoutHistoryFails() throws Exception {
        IndexAliasStore store = new IndexAliasStore(tempDir);
        store.load();
        store.putAlias("catalog", "catalog_1", null, 1);
        assertThrows(IllegalArgumentException.class, () -> store.rollback("catalog"));
    }
}
