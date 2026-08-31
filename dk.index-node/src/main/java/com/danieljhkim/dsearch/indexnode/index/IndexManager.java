package com.danieljhkim.dsearch.indexnode.index;

import com.danieljhkim.dsearch.common.config.AppConfig.FieldConfig;
import com.danieljhkim.dsearch.common.exception.SchemaMismatchException;
import com.danieljhkim.dsearch.common.exception.ShardNotFoundException;
import com.danieljhkim.dsearch.common.health.HealthHttpServer;
import com.danieljhkim.dsearch.common.model.SearchDocument;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.common.schema.IndexAlias;
import com.danieljhkim.dsearch.common.schema.IndexAliasStore;
import com.danieljhkim.dsearch.common.schema.IndexSchema;
import com.danieljhkim.dsearch.common.schema.IndexSchemaCompatibility;
import com.danieljhkim.dsearch.common.schema.ReindexJob;
import com.danieljhkim.dsearch.common.validation.PartitionIdValidator;
import com.danieljhkim.dsearch.ml.embedding.TextEmbedder;
import com.danieljhkim.dsearch.ml.embedding.TextEmbeddingService;
import com.danieljhkim.dsearch.proto.common.FacetRequest;
import com.danieljhkim.dsearch.proto.common.Filter;
import com.danieljhkim.dsearch.proto.common.SearchType;
import com.danieljhkim.dsearch.proto.index.RepresentativeQuery;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    private static final Counter LUCENE_COMMIT_OUTCOMES = Counter.build()
            .name("dsearch_lucene_commit_outcomes_total")
            .help("Lucene commit attempts by bounded outcome")
            .labelNames("outcome")
            .register();
    private static final Histogram LUCENE_COMMIT_DURATION = Histogram.build()
            .name("dsearch_lucene_commit_duration_seconds")
            .help("Duration of Lucene commit attempts")
            .register();
    private static final Gauge LUCENE_LAST_COMMIT = Gauge.build()
            .name("dsearch_lucene_last_successful_commit_timestamp_seconds")
            .help("Unix timestamp of the last successful Lucene commit")
            .register();
    private static final Gauge DISK_AVAILABLE_BYTES = Gauge.build()
            .name("dsearch_lucene_disk_available_bytes")
            .help("Usable bytes on the Lucene volume")
            .register();
    public static final int DEFAULT_MAX_BUFFERED_OPS_PER_SHARD = 100;
    public static final Duration DEFAULT_MAX_FLUSH_INTERVAL = Duration.ofSeconds(5);
    public static final long DEFAULT_MINIMUM_FREE_DISK_BYTES = 104857600L;

    /**
     * How many index/delete operations to buffer per shard before forcing a commit.
     */
    private final int maxBufferedOpsPerShard;

    /**
     * Max time to let buffered ops sit before being flushed by the background
     * thread.
     */
    private final Duration maxFlushInterval;

    private final long minimumFreeDiskBytes;

    private final Path baseDir;
    private final TextEmbedder embeddingService;
    private final Closeable ownedEmbeddingService;
    private final Map<String, ShardIndex> shardIndexes = new ConcurrentHashMap<>();

    // Per-shard in-memory buffer of pending operations
    private final Map<String, ShardBuffer> shardBuffers = new ConcurrentHashMap<>();

    private final ScheduledExecutorService flushScheduler;

    // Field configurations for filtering, sorting, and highlighting
    private final Map<String, FieldConfig> fieldConfigMap;
    private final IndexSchema expectedSchema;
    private final IndexAliasStore aliasStore;
    private final Map<String, SchemaMismatchException> unservableIndexes = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public IndexManager(Path baseDir) {
        this(baseDir, DEFAULT_MAX_BUFFERED_OPS_PER_SHARD, DEFAULT_MAX_FLUSH_INTERVAL, null);
    }

    public IndexManager(Path baseDir, int maxBufferedOpsPerShard, Duration maxFlushInterval) {
        this(baseDir, maxBufferedOpsPerShard, maxFlushInterval, null);
    }

    public IndexManager(
            Path baseDir, int maxBufferedOpsPerShard, Duration maxFlushInterval, List<FieldConfig> fieldConfigs) {
        this(
                baseDir,
                maxBufferedOpsPerShard,
                maxFlushInterval,
                fieldConfigs,
                null,
                true,
                DEFAULT_MINIMUM_FREE_DISK_BYTES);
    }

    public IndexManager(
            Path baseDir,
            int maxBufferedOpsPerShard,
            Duration maxFlushInterval,
            List<FieldConfig> fieldConfigs,
            long minimumFreeDiskBytes) {
        this(baseDir, maxBufferedOpsPerShard, maxFlushInterval, fieldConfigs, null, true, minimumFreeDiskBytes);
    }

    public IndexManager(
            Path baseDir,
            int maxBufferedOpsPerShard,
            Duration maxFlushInterval,
            List<FieldConfig> fieldConfigs,
            TextEmbedder embeddingService) {
        this(
                baseDir,
                maxBufferedOpsPerShard,
                maxFlushInterval,
                fieldConfigs,
                embeddingService,
                false,
                DEFAULT_MINIMUM_FREE_DISK_BYTES);
    }

    public IndexManager(
            Path baseDir,
            int maxBufferedOpsPerShard,
            Duration maxFlushInterval,
            List<FieldConfig> fieldConfigs,
            TextEmbedder embeddingService,
            long minimumFreeDiskBytes) {
        this(
                baseDir,
                maxBufferedOpsPerShard,
                maxFlushInterval,
                fieldConfigs,
                embeddingService,
                false,
                minimumFreeDiskBytes,
                null);
    }

    public IndexManager(
            Path baseDir,
            int maxBufferedOpsPerShard,
            Duration maxFlushInterval,
            List<FieldConfig> fieldConfigs,
            TextEmbedder embeddingService,
            long minimumFreeDiskBytes,
            IndexSchema expectedSchema) {
        this(
                baseDir,
                maxBufferedOpsPerShard,
                maxFlushInterval,
                fieldConfigs,
                embeddingService,
                false,
                minimumFreeDiskBytes,
                expectedSchema);
    }

    IndexManager(
            Path baseDir,
            int maxBufferedOpsPerShard,
            Duration maxFlushInterval,
            List<FieldConfig> fieldConfigs,
            TextEmbedder embeddingService,
            boolean ownsEmbeddingService,
            long minimumFreeDiskBytes) {
        this(
                baseDir,
                maxBufferedOpsPerShard,
                maxFlushInterval,
                fieldConfigs,
                embeddingService,
                ownsEmbeddingService,
                minimumFreeDiskBytes,
                null);
    }

    IndexManager(
            Path baseDir,
            int maxBufferedOpsPerShard,
            Duration maxFlushInterval,
            List<FieldConfig> fieldConfigs,
            TextEmbedder embeddingService,
            boolean ownsEmbeddingService,
            long minimumFreeDiskBytes,
            IndexSchema expectedSchema) {
        if (maxBufferedOpsPerShard < 1) {
            throw new IllegalArgumentException("maxBufferedOpsPerShard must be greater than 0");
        }
        if (maxFlushInterval == null || maxFlushInterval.compareTo(Duration.ZERO) <= 0) {
            throw new IllegalArgumentException("maxFlushInterval must be greater than zero");
        }
        if (minimumFreeDiskBytes < 0) {
            throw new IllegalArgumentException("minimumFreeDiskBytes must not be negative");
        }
        this.baseDir = baseDir;
        this.maxBufferedOpsPerShard = maxBufferedOpsPerShard;
        this.maxFlushInterval = maxFlushInterval;
        this.minimumFreeDiskBytes = minimumFreeDiskBytes;
        TextEmbedder resolvedEmbedder = embeddingService;
        if (resolvedEmbedder == null) {
            if (!ownsEmbeddingService) {
                throw new NullPointerException("embeddingService");
            }
            resolvedEmbedder = new TextEmbeddingService();
        }
        this.embeddingService = resolvedEmbedder;
        this.ownedEmbeddingService =
                ownsEmbeddingService && resolvedEmbedder instanceof Closeable closeable ? closeable : null;

        // Build field config map
        this.fieldConfigMap = new HashMap<>();
        if (fieldConfigs != null) {
            for (FieldConfig fc : fieldConfigs) {
                this.fieldConfigMap.put(fc.getName(), fc);
            }
        }
        this.expectedSchema = ShardIndex.resolveRuntimeSchema(expectedSchema, this.fieldConfigMap, this.embeddingService);
        this.aliasStore = new IndexAliasStore(this.baseDir);

        ScheduledExecutorService scheduler = null;
        try {
            this.aliasStore.load();
            loadExistingShards();

            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "index-flush-scheduler");
                t.setDaemon(true);
                return t;
            });

            // Periodic flush based on time
            long intervalMillis = Math.max(1L, maxFlushInterval.toMillis());
            scheduler.scheduleAtFixedRate(
                    this::flushBuffersOnSchedule, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
            this.flushScheduler = scheduler;
        } catch (IOException e) {
            RuntimeException wrapped = new RuntimeException("Failed to load index alias metadata from " + baseDir, e);
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            closeInitializingResources(wrapped);
            throw wrapped;
        } catch (RuntimeException e) {
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            closeInitializingResources(e);
            throw e;
        }
    }

    /**
     * Reports whether this manager can safely serve Lucene traffic now. It deliberately does not
     * flush or write during a probe: opening the manager already validated Lucene state, while the
     * probe verifies that its volume remains writable and has enough room for future commits.
     */
    public HealthHttpServer.Readiness readiness() {
        if (closed) {
            return HealthHttpServer.Readiness.notReady("index_manager_closed");
        }
        if (embeddingService instanceof TextEmbeddingService textEmbeddingService && !textEmbeddingService.isReady()) {
            return HealthHttpServer.Readiness.notReady("embedding_model_not_ready");
        }
        try {
            if (!Files.isDirectory(baseDir) || !Files.isWritable(baseDir)) {
                return HealthHttpServer.Readiness.notReady("lucene_directory_not_writable");
            }
            FileStore store = Files.getFileStore(baseDir);
            long usableSpace = store.getUsableSpace();
            DISK_AVAILABLE_BYTES.set(usableSpace);
            if (usableSpace < minimumFreeDiskBytes) {
                return HealthHttpServer.Readiness.notReady("disk_space_below_threshold");
            }
            return HealthHttpServer.Readiness.up();
        } catch (IOException e) {
            return HealthHttpServer.Readiness.notReady(
                    "lucene_storage_unavailable:" + e.getClass().getSimpleName());
        }
    }

    /**
     * On startup, scan baseDir for shard directories (shard-0, shard-1, ...)
     * and create ShardIndex instances for each one.
     */
    private void loadExistingShards() {
        try {
            Files.createDirectories(baseDir);
            List<String> shardDirectoryNames;
            try (Stream<Path> paths = Files.list(baseDir)) {
                shardDirectoryNames = paths.filter(Files::isDirectory)
                        .map(Path::getFileName)
                        .map(Path::toString)
                        .filter(name -> name.startsWith(SHARD_PREFIX))
                        .sorted()
                        .toList();
            }
            for (String dirName : shardDirectoryNames) {
                String shardId = dirName.substring(SHARD_PREFIX.length());
                openExistingShard(shardId);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load existing shard indexes from " + baseDir, e);
        }
    }

    private void openExistingShard(String shardId) {
        try {
            ShardIndex shardIndex = new ShardIndex(shardId, baseDir, fieldConfigMap, embeddingService, expectedSchema, true);
            shardIndexes.put(shardId, shardIndex);
            shardBuffers.put(shardId, new ShardBuffer());
            unservableIndexes.remove(shardId);
            try {
                aliasStore.ensureIdentityAlias(shardId);
            } catch (IOException e) {
                throw new RuntimeException("Failed to persist identity alias for " + shardId, e);
            }
        } catch (SchemaMismatchException e) {
            LOGGER.log(Level.SEVERE, "Refusing to serve incompatible index " + shardId + ": " + e.getMessage());
            unservableIndexes.put(shardId, e);
            ShardIndex readable = new ShardIndex(shardId, baseDir, fieldConfigMap, embeddingService, expectedSchema, false);
            shardIndexes.put(shardId, readable);
            shardBuffers.put(shardId, new ShardBuffer());
        }
    }

    private void closeInitializingResources(Throwable cause) {
        for (ShardIndex shardIndex : shardIndexes.values()) {
            try {
                shardIndex.close();
            } catch (Exception e) {
                cause.addSuppressed(e);
            }
        }
        shardIndexes.clear();
        shardBuffers.clear();
        if (ownedEmbeddingService != null) {
            try {
                ownedEmbeddingService.close();
            } catch (Exception e) {
                cause.addSuppressed(e);
            }
        }
    }

    private ShardIndex getOrCreateShard(String shardId) {
        ensureServable(shardId);
        ShardIndex index = shardIndexes.computeIfAbsent(shardId, id -> {
            ShardIndex si = new ShardIndex(id, baseDir, fieldConfigMap, embeddingService, expectedSchema, true);
            shardBuffers.put(id, new ShardBuffer());
            try {
                aliasStore.ensureIdentityAlias(id);
            } catch (IOException e) {
                throw new RuntimeException("Failed to persist identity alias for " + id, e);
            }
            return si;
        });
        shardBuffers.computeIfAbsent(shardId, id -> new ShardBuffer());
        return index;
    }

    public IndexSchema expectedSchema() {
        return expectedSchema;
    }

    public IndexAliasStore aliasStore() {
        return aliasStore;
    }

    public String resolvePhysicalIndex(String aliasOrIndex) {
        return aliasStore.resolve(aliasOrIndex);
    }

    private void ensureServable(String physicalIndex) {
        SchemaMismatchException mismatch = unservableIndexes.get(physicalIndex);
        if (mismatch != null) {
            throw mismatch;
        }
    }

    private ShardIndex requireShard(String physicalIndex) {
        ShardIndex shardIndex = shardIndexes.get(physicalIndex);
        if (shardIndex == null) {
            throw new ShardNotFoundException(physicalIndex);
        }
        return shardIndex;
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
        String physicalIndex = resolvePhysicalIndex(partitionId);
        ShardIndex shardIndex = getOrCreateShard(physicalIndex);
        ShardBuffer buffer = getBuffer(physicalIndex);

        buffer.lock.lock();
        try {
            buffer.add(BufferedOperation.index(doc));
            if (buffer.pendingOperations.size() >= maxBufferedOpsPerShard) {
                flushShardBufferLocked(physicalIndex, shardIndex, buffer);
            }
        } finally {
            buffer.lock.unlock();
        }
    }

    /**
     * Indexes a document and does not return until Lucene has committed it.
     *
     * <p>The per-shard lock keeps this operation ordered with buffered writes. Any older
     * buffered operations are committed in the same durability boundary before this method
     * returns. If the commit fails, the exception is propagated and no durable acknowledgement
     * may be issued by the caller.
     */
    public void indexDocumentDurably(String partitionId, SearchDocument doc) throws IOException {
        String physicalIndex = resolvePhysicalIndex(partitionId);
        ShardIndex shardIndex = getOrCreateShard(physicalIndex);
        ShardBuffer buffer = getBuffer(physicalIndex);

        buffer.lock.lock();
        try {
            buffer.add(BufferedOperation.index(doc));
            flushShardBufferLocked(physicalIndex, shardIndex, buffer);
        } finally {
            buffer.lock.unlock();
        }
    }

    /**
     * Buffered delete: same semantics as indexDocument — delete ops are buffered
     * and only flushed/committed when thresholds/timeouts are reached.
     */
    public void deleteDocument(String partitionId, String docId) throws IOException {
        String physicalIndex = resolvePhysicalIndex(partitionId);
        ShardIndex shardIndex = getOrCreateShard(physicalIndex);
        ShardBuffer buffer = getBuffer(physicalIndex);
        buffer.lock.lock();
        try {
            buffer.add(BufferedOperation.delete(docId));
            if (buffer.pendingOperations.size() >= maxBufferedOpsPerShard) {
                flushShardBufferLocked(physicalIndex, shardIndex, buffer);
            }
        } finally {
            buffer.lock.unlock();
        }
    }

    /**
     * Deletes a document and does not return until Lucene has committed the deletion.
     *
     * <p>The per-shard lock keeps this operation ordered with buffered writes and deletes. Any older
     * buffered operations are committed in the same durability boundary before this method returns.
     * If the commit fails, the exception is propagated and no durable acknowledgement may be issued
     * by the caller.
     */
    public void deleteDocumentDurably(String partitionId, String docId) throws IOException {
        String physicalIndex = resolvePhysicalIndex(partitionId);
        ShardIndex shardIndex = getOrCreateShard(physicalIndex);
        ShardBuffer buffer = getBuffer(physicalIndex);

        buffer.lock.lock();
        try {
            buffer.add(BufferedOperation.delete(docId));
            flushShardBufferLocked(physicalIndex, shardIndex, buffer);
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
        String physicalIndex = resolvePhysicalIndex(partitionId);
        ensureServable(physicalIndex);
        ShardIndex shardIndex = shardIndexes.get(physicalIndex);
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

    public CreatedIndex createIndex(String indexName, String alias, IndexSchema schema) throws IOException {
        PartitionIdValidator.validate(indexName);
        String resolvedAlias = alias == null || alias.isBlank() ? indexName : alias;
        PartitionIdValidator.validate(resolvedAlias);
        IndexSchema toPersist = schema == null ? expectedSchema : ShardIndex.resolveRuntimeSchema(schema, fieldConfigMap, embeddingService);
        if (schema != null) {
            IndexSchemaCompatibility.requireCompatible(toPersist, expectedSchema);
        }
        IndexAlias existing = aliasStore.getAlias(resolvedAlias);
        if (existing != null && shardIndexes.containsKey(existing.getIndexName())) {
            throw new IllegalArgumentException("Alias '" + resolvedAlias + "' already points to index " + existing.getIndexName());
        }
        if (shardIndexes.containsKey(indexName)) {
            throw new IllegalArgumentException("Index '" + indexName + "' already exists");
        }
        getOrCreateShard(indexName);
        aliasStore.putAlias(
                resolvedAlias,
                indexName,
                existing == null ? null : existing.getPreviousIndexName(),
                existing == null ? 1 : existing.getGeneration());
        return new CreatedIndex(indexName, resolvedAlias, toPersist);
    }

    public InspectedSchema inspectSchema(String indexOrAlias) {
        PartitionIdValidator.validate(indexOrAlias);
        String physicalIndex = resolvePhysicalIndex(indexOrAlias);
        ShardIndex shardIndex = shardIndexes.get(physicalIndex);
        if (shardIndex == null) {
            throw new ShardNotFoundException(physicalIndex);
        }
        IndexAlias alias = aliasStore.getAlias(indexOrAlias);
        String aliasName = alias != null ? alias.getAlias() : indexOrAlias.equals(physicalIndex) ? physicalIndex : indexOrAlias;
        return new InspectedSchema(physicalIndex, aliasName, shardIndex.getSchema());
    }

    public ReindexResult reindex(
            String sourceAlias, String targetIndex, IndexSchema schema, List<RepresentativeQuery> verificationQueries)
            throws IOException {
        PartitionIdValidator.validate(sourceAlias);
        String sourceIndex = resolvePhysicalIndex(sourceAlias);
        ShardIndex source = shardIndexes.get(sourceIndex);
        if (source == null) {
            throw new ShardNotFoundException(sourceIndex);
        }
        String resolvedTarget = targetIndex == null || targetIndex.isBlank()
                ? nextPhysicalIndex(sourceAlias)
                : targetIndex;
        PartitionIdValidator.validate(resolvedTarget);
        if (resolvedTarget.equals(sourceIndex)) {
            throw new IllegalArgumentException("Reindex target must be distinct from source index " + sourceIndex);
        }
        IndexSchema targetSchema =
                schema == null ? expectedSchema : ShardIndex.resolveRuntimeSchema(schema, fieldConfigMap, embeddingService);
        ReindexJob job = new ReindexJob();
        job.setJobId(UUID.randomUUID().toString());
        job.setSourceAlias(sourceAlias);
        job.setSourceIndex(sourceIndex);
        job.setTargetIndex(resolvedTarget);
        job.setStatus(ReindexJob.STATUS_COPYING);
        job.setSourceCount(source.countDocuments());
        aliasStore.saveJob(job);

        ShardIndex target = getOrCreateShard(resolvedTarget);
        try {
            for (SearchDocument document : source.exportDocuments()) {
                target.index(document);
            }
            target.commit();
            long targetCount = target.countDocuments();
            job.setTargetCount(targetCount);
            boolean verified = targetCount == job.getSourceCount()
                    && verifyQueries(source, target, verificationQueries);
            job.setVerificationPassed(verified);
            job.setStatus(verified ? ReindexJob.STATUS_VERIFIED : ReindexJob.STATUS_FAILED);
            if (!verified) {
                job.setError("reindex verification failed for counts or representative queries");
            }
            aliasStore.saveJob(job);
            return new ReindexResult(
                    verified,
                    sourceIndex,
                    resolvedTarget,
                    job.getSourceCount(),
                    targetCount,
                    verified,
                    job.getError());
        } catch (RuntimeException | IOException e) {
            job.setStatus(ReindexJob.STATUS_INTERRUPTED);
            job.setError(e.getMessage());
            try {
                aliasStore.saveJob(job);
            } catch (IOException persistError) {
                e.addSuppressed(persistError);
            }
            throw e;
        }
    }

    public IndexAlias swapAlias(String alias, String targetIndex) throws IOException {
        PartitionIdValidator.validate(alias);
        PartitionIdValidator.validate(targetIndex);
        ensureServable(targetIndex);
        requireShard(targetIndex);
        IndexAlias current = aliasStore.getAlias(alias);
        String currentIndex = current == null ? alias : current.getIndexName();
        if (!targetIndex.equals(currentIndex)) {
            ReindexJob verified = aliasStore.current().getReindexJobs().values().stream()
                    .filter(job -> alias.equals(job.getSourceAlias()) && targetIndex.equals(job.getTargetIndex()))
                    .findFirst()
                    .orElse(null);
            if (verified != null && !verified.isComplete()) {
                throw new IllegalArgumentException("Cannot swap alias '" + alias + "' until reindex of "
                        + targetIndex + " is verified");
            }
        }
        return aliasStore.swap(alias, targetIndex);
    }

    public IndexAlias rollbackAlias(String alias) throws IOException {
        PartitionIdValidator.validate(alias);
        IndexAlias current = aliasStore.getAlias(alias);
        if (current == null) {
            throw new IllegalArgumentException("Unknown alias '" + alias + "'");
        }
        String previous = current.getPreviousIndexName();
        if (previous == null || previous.isBlank()) {
            throw new IllegalArgumentException("No previous alias target to roll back for '" + alias + "'");
        }
        requireShard(previous);
        return aliasStore.rollback(alias);
    }

    public String nextPhysicalIndex(String alias) {
        PartitionIdValidator.validate(alias);
        IndexAlias current = aliasStore.getAlias(alias);
        int generation = current == null ? 2 : current.getGeneration() + 1;
        String candidate = alias + "_" + generation;
        while (candidate.length() <= 64
                && (shardIndexes.containsKey(candidate) || candidate.equals(aliasStore.resolve(alias)))) {
            generation++;
            candidate = alias + "_" + generation;
        }
        if (candidate.length() > 64) {
            throw new IllegalArgumentException("Unable to allocate a physical index name for alias '" + alias + "'");
        }
        return candidate;
    }

    private boolean verifyQueries(ShardIndex source, ShardIndex target, List<RepresentativeQuery> verificationQueries) {
        if (verificationQueries == null || verificationQueries.isEmpty()) {
            return true;
        }
        for (RepresentativeQuery query : verificationQueries) {
            int size = query.getSize() > 0 ? query.getSize() : 10;
            SearchType searchType =
                    query.getSearchType() == SearchType.SEARCH_TYPE_UNSPECIFIED ? SearchType.BM25 : query.getSearchType();
            SearchResult sourceResult = searchOn(source, query.getQuery(), size, searchType);
            SearchResult targetResult = searchOn(target, query.getQuery(), size, searchType);
            if (sourceResult.getTotalHits() != targetResult.getTotalHits()) {
                return false;
            }
        }
        return true;
    }

    private SearchResult searchOn(ShardIndex shardIndex, String query, int size, SearchType searchType) {
        return switch (searchType) {
            case SearchType.SEMANTIC -> shardIndex.semanticSearch(query, size, 0, null, false, null);
            default -> shardIndex.search(query, size, 0, null, false, null);
        };
    }

    public record CreatedIndex(String indexName, String alias, IndexSchema schema) {}

    public record InspectedSchema(String indexName, String alias, IndexSchema schema) {}

    public record ReindexResult(
            boolean success,
            String sourceIndex,
            String targetIndex,
            long sourceCount,
            long targetCount,
            boolean verificationPassed,
            String error) {}

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
                commitShard(shardIndex);
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

        commitShard(shardIndex);
        // Clear the buffer and reset counters
        buffer.pendingOperations.clear();
        buffer.firstPendingNanos = 0L;
        long flushNanos = System.nanoTime();

        LOGGER.fine(() -> "Flushed shard buffer for partitionId=" + partitionId + " at " + flushNanos);
    }

    void commitShard(ShardIndex shardIndex) throws IOException {
        long started = System.nanoTime();
        try {
            shardIndex.commit();
            LUCENE_COMMIT_OUTCOMES.labels("success").inc();
            LUCENE_LAST_COMMIT.set(System.currentTimeMillis() / 1000.0);
        } catch (IOException e) {
            LUCENE_COMMIT_OUTCOMES.labels("failure").inc();
            throw e;
        } finally {
            LUCENE_COMMIT_DURATION.observe((System.nanoTime() - started) / 1_000_000_000.0);
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
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
