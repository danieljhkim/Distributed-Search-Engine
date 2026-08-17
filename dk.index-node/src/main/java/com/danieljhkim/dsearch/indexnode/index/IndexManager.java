package com.danieljhkim.dsearch.indexnode.index;

import com.danieljhkim.dsearch.common.config.AppConfig.FieldConfig;
import com.danieljhkim.dsearch.common.model.SearchDocument;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.ml.embedding.TextEmbedder;
import com.danieljhkim.dsearch.ml.embedding.TextEmbeddingService;
import com.danieljhkim.dsearch.proto.common.FacetRequest;
import com.danieljhkim.dsearch.proto.common.Filter;
import com.danieljhkim.dsearch.proto.common.SearchType;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    public static final int DEFAULT_MAX_BUFFERED_OPS_PER_SHARD = 100;
    public static final Duration DEFAULT_MAX_FLUSH_INTERVAL = Duration.ofSeconds(5);

    /**
     * How many index/delete operations to buffer per shard before forcing a commit.
     */
    private final int maxBufferedOpsPerShard;

    /**
     * Max time to let buffered ops sit before being flushed by the background
     * thread.
     */
    private final Duration maxFlushInterval;

    private final Path baseDir;
    private final TextEmbedder embeddingService;
    private final Closeable ownedEmbeddingService;
    private final Map<String, ShardIndex> shardIndexes = new ConcurrentHashMap<>();

    // Per-shard in-memory buffer of pending operations
    private final Map<String, ShardBuffer> shardBuffers = new ConcurrentHashMap<>();

    private final ScheduledExecutorService flushScheduler;

    // Field configurations for filtering, sorting, and highlighting
    private final Map<String, FieldConfig> fieldConfigMap;

    public IndexManager(Path baseDir) {
        this(baseDir, DEFAULT_MAX_BUFFERED_OPS_PER_SHARD, DEFAULT_MAX_FLUSH_INTERVAL, null);
    }

    public IndexManager(Path baseDir, int maxBufferedOpsPerShard, Duration maxFlushInterval) {
        this(baseDir, maxBufferedOpsPerShard, maxFlushInterval, null);
    }

    public IndexManager(
            Path baseDir, int maxBufferedOpsPerShard, Duration maxFlushInterval, List<FieldConfig> fieldConfigs) {
        this(baseDir, maxBufferedOpsPerShard, maxFlushInterval, fieldConfigs, new TextEmbeddingService(), true);
    }

    public IndexManager(
            Path baseDir,
            int maxBufferedOpsPerShard,
            Duration maxFlushInterval,
            List<FieldConfig> fieldConfigs,
            TextEmbedder embeddingService) {
        this(baseDir, maxBufferedOpsPerShard, maxFlushInterval, fieldConfigs, embeddingService, false);
    }

    private IndexManager(
            Path baseDir,
            int maxBufferedOpsPerShard,
            Duration maxFlushInterval,
            List<FieldConfig> fieldConfigs,
            TextEmbedder embeddingService,
            boolean ownsEmbeddingService) {
        if (maxBufferedOpsPerShard < 1) {
            throw new IllegalArgumentException("maxBufferedOpsPerShard must be greater than 0");
        }
        if (maxFlushInterval == null || maxFlushInterval.compareTo(Duration.ZERO) <= 0) {
            throw new IllegalArgumentException("maxFlushInterval must be greater than zero");
        }
        this.baseDir = baseDir;
        this.maxBufferedOpsPerShard = maxBufferedOpsPerShard;
        this.maxFlushInterval = maxFlushInterval;
        this.embeddingService = Objects.requireNonNull(embeddingService, "embeddingService");
        this.ownedEmbeddingService =
                ownsEmbeddingService && embeddingService instanceof Closeable closeable ? closeable : null;

        // Build field config map
        this.fieldConfigMap = new HashMap<>();
        if (fieldConfigs != null) {
            for (FieldConfig fc : fieldConfigs) {
                this.fieldConfigMap.put(fc.getName(), fc);
            }
        }

        loadExistingShards();

        this.flushScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "index-flush-scheduler");
            t.setDaemon(true);
            return t;
        });

        // Periodic flush based on time
        long intervalMillis = Math.max(1L, maxFlushInterval.toMillis());
        flushScheduler.scheduleAtFixedRate(
                this::flushBuffersOnSchedule, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * On startup, scan baseDir for shard directories (shard-0, shard-1, ...)
     * and create ShardIndex instances for each one.
     */
    private void loadExistingShards() {
        try {
            Files.createDirectories(baseDir);
            try (Stream<Path> paths = Files.list(baseDir)) {
                paths.filter(Files::isDirectory)
                        .map(Path::getFileName)
                        .map(Path::toString)
                        .filter(name -> name.startsWith(SHARD_PREFIX))
                        .forEach(dirName -> {
                            String shardId = dirName.substring(SHARD_PREFIX.length());
                            ShardIndex shardIndex = new ShardIndex(shardId, baseDir, fieldConfigMap, embeddingService);
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
            ShardIndex si = new ShardIndex(id, baseDir, fieldConfigMap, embeddingService);
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
            buffer.add(BufferedOperation.index(doc));
            if (buffer.pendingOperations.size() >= maxBufferedOpsPerShard) {
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
            buffer.add(BufferedOperation.delete(docId));
            if (buffer.pendingOperations.size() >= maxBufferedOpsPerShard) {
                flushShardBufferLocked(partitionId, shardIndex, buffer);
            }
        } finally {
            buffer.lock.unlock();
        }
    }

    /**
     * Search reads from the current committed state (backward compatible).
     */
    public SearchResult searchDocument(String partitionId, String query, int limit, int from, SearchType searchType)
            throws IOException {
        return searchDocument(partitionId, query, limit, from, searchType, null, false, null);
    }

    /**
     * Search with filters, highlighting, and facets.
     * Newly indexed docs may not be visible until a flush/commit happens.
     */
    public SearchResult searchDocument(
            String partitionId,
            String query,
            int limit,
            int from,
            SearchType searchType,
            List<Filter> filters,
            boolean highlight,
            List<FacetRequest> facetRequests)
            throws IOException {
        ShardIndex shardIndex = shardIndexes.get(partitionId);
        if (shardIndex == null) {
            return new SearchResult(new ArrayList<>(), 0);
        }
        return switch (searchType) {
            case SearchType.SEMANTIC ->
                shardIndex.semanticSearch(query, limit, from, filters, highlight, facetRequests);
            case SearchType.BM25 -> shardIndex.search(query, limit, from, filters, highlight, facetRequests);
            default -> shardIndex.search(query, limit, from, filters, highlight, facetRequests);
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
                if (buffer.pendingOperations.isEmpty()) {
                    continue;
                }
                long elapsedNanos = nowNanos - buffer.firstPendingNanos;
                if (elapsedNanos >= maxFlushInterval.toNanos()) {
                    try {
                        flushShardBufferLocked(partitionId, shardIndex, buffer);
                    } catch (IOException e) {
                        LOGGER.log(Level.SEVERE, "Failed to flush shard buffer for partitionId=" + partitionId, e);
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
    private void flushShardBufferLocked(String partitionId, ShardIndex shardIndex, ShardBuffer buffer)
            throws IOException {
        if (buffer.pendingOperations.isEmpty()) {
            return;
        }
        // Apply buffered operations
        for (BufferedOperation operation : buffer.pendingOperations) {
            operation.apply(shardIndex);
        }

        shardIndex.commit();
        // Clear the buffer and reset counters
        buffer.pendingOperations.clear();
        buffer.firstPendingNanos = 0L;
        long flushNanos = System.nanoTime();

        LOGGER.fine(() -> "Flushed shard buffer for partitionId=" + partitionId + " at " + flushNanos);
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

        IOException first = null;
        try {
            // Final flush & close all shards
            for (Map.Entry<String, ShardIndex> entry : shardIndexes.entrySet()) {
                String shardId = entry.getKey();
                ShardIndex shardIndex = entry.getValue();
                ShardBuffer buffer = getBuffer(shardId);

                buffer.lock.lock();
                try {
                    flushShardBufferLocked(shardId, shardIndex, buffer);
                    shardIndex.close();
                } catch (IOException e) {
                    if (first == null) {
                        first = e;
                    } else {
                        first.addSuppressed(e);
                    }
                } finally {
                    buffer.lock.unlock();
                }
            }
        } finally {
            if (ownedEmbeddingService != null) {
                try {
                    ownedEmbeddingService.close();
                } catch (IOException e) {
                    if (first == null) {
                        first = e;
                    } else {
                        first.addSuppressed(e);
                    }
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    /**
     * Per-shard buffer of pending index/delete operations.
     * Guarded by a ReentrantLock for thread-safety.
     */
    private static final class ShardBuffer {
        final ReentrantLock lock = new ReentrantLock();
        final List<BufferedOperation> pendingOperations = new ArrayList<>();
        long firstPendingNanos = 0L;

        void add(BufferedOperation operation) {
            if (pendingOperations.isEmpty()) {
                firstPendingNanos = System.nanoTime();
            }
            pendingOperations.add(operation);
        }
    }

    private record BufferedOperation(OperationType type, SearchDocument document, String docId) {
        static BufferedOperation index(SearchDocument document) {
            return new BufferedOperation(OperationType.INDEX, document, null);
        }

        static BufferedOperation delete(String docId) {
            return new BufferedOperation(OperationType.DELETE, null, docId);
        }

        void apply(ShardIndex shardIndex) throws IOException {
            switch (type) {
                case INDEX -> shardIndex.index(document);
                case DELETE -> shardIndex.delete(docId);
            }
        }
    }

    private enum OperationType {
        INDEX,
        DELETE
    }
}
