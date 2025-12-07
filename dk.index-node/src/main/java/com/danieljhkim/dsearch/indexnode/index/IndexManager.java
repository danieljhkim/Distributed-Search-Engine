package com.danieljhkim.dsearch.indexnode.index;

import com.danieljhkim.dsearch.common.enums.SearchType;
import com.danieljhkim.dsearch.common.model.SearchDocument;
import com.danieljhkim.dsearch.common.model.SearchResult;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

public class IndexManager implements Closeable {

    private static final Logger LOGGER = Logger.getLogger(IndexManager.class.getName());
    private static final String SHARD_PREFIX = "shard-";

    /**
     * How many index/delete operations to buffer per shard before forcing a commit.
     */
    private final int maxBufferedOpsPerShard;

    /**
     * Max time to let buffered ops sit before being flushed by the background thread.
     */
    private final Duration maxFlushInterval;

    private final Path baseDir;
    private final Map<String, ShardIndex> shardIndexes = new ConcurrentHashMap<>();

    // Per-shard in-memory buffer of pending operations
    private final Map<String, ShardBuffer> shardBuffers = new ConcurrentHashMap<>();

    private final ScheduledExecutorService flushScheduler;

    public IndexManager(Path baseDir) {
        this(baseDir, 1, Duration.ofSeconds(6));
    }

    public IndexManager(Path baseDir, int maxBufferedOpsPerShard, Duration maxFlushInterval) {
        this.baseDir = baseDir;
        this.maxBufferedOpsPerShard = maxBufferedOpsPerShard;
        this.maxFlushInterval = maxFlushInterval;

        loadExistingShards();

        this.flushScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "index-flush-scheduler");
            t.setDaemon(true);
            return t;
        });

        // Periodic flush based on time
        long intervalMillis = maxFlushInterval.toMillis();
        flushScheduler.scheduleAtFixedRate(
                this::flushBuffersOnSchedule,
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * On startup, scan baseDir for shard directories (shard-0, shard-1, ...)
     * and create ShardIndex instances for each one.
     */
    private void loadExistingShards() {
        try {
            Files.createDirectories(baseDir);
            try (Stream<Path> paths = Files.list(baseDir)) {
                paths
                        .filter(Files::isDirectory)
                        .map(Path::getFileName)
                        .map(Path::toString)
                        .filter(name -> name.startsWith(SHARD_PREFIX))
                        .forEach(dirName -> {
                            String shardId = dirName.substring(SHARD_PREFIX.length());
                            ShardIndex shardIndex = new ShardIndex(shardId, baseDir);
                            shardIndexes.put(shardId, shardIndex);
                            shardBuffers.put(shardId, new ShardBuffer());
                        });
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load existing shard indexes from " + baseDir, e);
        }
    }

    private ShardIndex getOrCreateShard(String shardId) {
        ShardIndex index = shardIndexes.computeIfAbsent(shardId, id -> {
            ShardIndex si = new ShardIndex(id, baseDir);
            shardBuffers.put(id, new ShardBuffer());
            return si;
        });
        shardBuffers.computeIfAbsent(shardId, id -> new ShardBuffer());
        return index;
    }

    private ShardBuffer getBuffer(String shardId) {
        return shardBuffers.computeIfAbsent(shardId, id -> new ShardBuffer());
    }

    /**
     * Buffered indexing: we append the doc to an in-memory buffer and only
     * flush to Lucene (index + commit) when:
     * - we hit maxBufferedOpsPerShard, OR
     * - the background thread sees that maxFlushInterval has passed.
     */
    public void indexDocument(String partitionId, SearchDocument doc) throws IOException {
        ShardIndex shardIndex = getOrCreateShard(partitionId);
        ShardBuffer buffer = getBuffer(partitionId);

        buffer.lock.lock();
        try {
            buffer.pendingDocs.add(doc);
            buffer.pendingOpsCount++;
            // Optional: keep a doc counter if you want doc-count-based metrics
            if (buffer.pendingOpsCount >= maxBufferedOpsPerShard) {
                flushShardBufferLocked(partitionId, shardIndex, buffer);
            }
        } finally {
            buffer.lock.unlock();
        }
    }

    /**
     * Buffered delete: same semantics as indexDocument — delete ops are buffered
     * and only flushed/committed when thresholds/timeouts are reached.
     */
    public void deleteDocument(String partitionId, String docId) throws IOException {
        ShardIndex shardIndex = getOrCreateShard(partitionId);
        ShardBuffer buffer = getBuffer(partitionId);
        buffer.lock.lock();
        try {
            buffer.pendingDeletes.add(docId);
            buffer.pendingOpsCount++;
            if (buffer.pendingOpsCount >= maxBufferedOpsPerShard) {
                flushShardBufferLocked(partitionId, shardIndex, buffer);
            }
        } finally {
            buffer.lock.unlock();
        }
    }

    /**
     * Search reads from the current committed state. There is a trade-off:
     * - Newly indexed docs may not be visible until a flush/commit happens.
     * - In most systems, that small delay is acceptable.
     */
    public SearchResult searchDocument(
            String partitionId,
            String query,
            int limit,
            int from,
            SearchType searchType
    ) throws IOException {
        ShardIndex shardIndex = shardIndexes.get(partitionId);
        if (shardIndex == null) {
            return new SearchResult(new ArrayList<>(), 0);
        }
        return switch (searchType) {
            case SearchType.SEMANTIC -> shardIndex.semanticSearch(query, limit, from);
            case SearchType.BM25 -> shardIndex.search(query, limit, from);
            default -> shardIndex.search(query, limit, from);
        };
    }

    /**
     * Force a synchronous commit of all shards:
     * - flushes all in-memory buffers
     * - calls commit() on every ShardIndex
     */
    public void commitAll() throws IOException {
        // First, flush all buffers
        for (Map.Entry<String, ShardIndex> entry : shardIndexes.entrySet()) {
            String partitionId = entry.getKey();
            ShardIndex shardIndex = entry.getValue();
            ShardBuffer buffer = getBuffer(partitionId);

            buffer.lock.lock();
            try {
                flushShardBufferLocked(partitionId, shardIndex, buffer);
                // Also ensure a final commit even if there were no buffered ops.
                shardIndex.commit();
            } finally {
                buffer.lock.unlock();
            }
        }
    }

    /**
     * Background scheduled task: scans all shard buffers and flushes any that
     * have pending ops that have been sitting longer than maxFlushInterval.
     */
    private void flushBuffersOnSchedule() {
        long nowNanos = System.nanoTime();
        for (Map.Entry<String, ShardIndex> entry : shardIndexes.entrySet()) {
            String partitionId = entry.getKey();
            ShardIndex shardIndex = entry.getValue();
            ShardBuffer buffer = getBuffer(partitionId);

            // Avoid blocking the scheduler if a shard is currently being flushed manually
            if (!buffer.lock.tryLock()) {
                continue;
            }
            try {
                if (buffer.pendingOpsCount == 0) {
                    continue;
                }
                long elapsedNanos = nowNanos - buffer.lastFlushNanos;
                if (elapsedNanos >= maxFlushInterval.toNanos()) {
                    try {
                        flushShardBufferLocked(partitionId, shardIndex, buffer);
                    } catch (IOException e) {
                        LOGGER.log(Level.SEVERE,
                                "Failed to flush shard buffer for partitionId=" + partitionId, e);
                    }
                }
            } finally {
                buffer.lock.unlock();
            }
        }
    }

    /**
     * Flushes buffered docs and deletes for a single shard.
     * Assumes buffer.lock is already held.
     */
    private void flushShardBufferLocked(String partitionId, ShardIndex shardIndex, ShardBuffer buffer) throws IOException {
        if (buffer.pendingOpsCount == 0) {
            return;
        }
        // Apply buffered operations
        for (SearchDocument doc : buffer.pendingDocs) {
            shardIndex.index(doc);
        }
        for (String docId : buffer.pendingDeletes) {
            shardIndex.delete(docId);
        }

        shardIndex.commit();
        // Clear the buffer and reset counters
        buffer.pendingDocs.clear();
        buffer.pendingDeletes.clear();
        buffer.pendingOpsCount = 0;
        buffer.lastFlushNanos = System.nanoTime();

        LOGGER.fine(() -> "Flushed shard buffer for partitionId="
                + partitionId + " at " + buffer.lastFlushNanos);
    }

    @Override
    public void close() throws IOException {
        flushScheduler.shutdown();
        try {
            if (!flushScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                flushScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            flushScheduler.shutdownNow();
        }

        // Final flush & close all shards
        for (Map.Entry<String, ShardIndex> entry : shardIndexes.entrySet()) {
            String shardId = entry.getKey();
            ShardIndex shardIndex = entry.getValue();
            ShardBuffer buffer = getBuffer(shardId);

            buffer.lock.lock();
            try {
                flushShardBufferLocked(shardId, shardIndex, buffer);
                shardIndex.close();
            } finally {
                buffer.lock.unlock();
            }
        }
    }

    /**
     * Per-shard buffer of pending index/delete operations.
     * Guarded by a ReentrantLock for thread-safety.
     */
    private static final class ShardBuffer {
        final ReentrantLock lock = new ReentrantLock();
        final List<SearchDocument> pendingDocs = new ArrayList<>();
        final List<String> pendingDeletes = new ArrayList<>();
        int pendingOpsCount = 0;
        long lastFlushNanos = System.nanoTime();
    }

}