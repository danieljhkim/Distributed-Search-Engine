package com.dk.search.querynode.search;

import com.dk.search.common.model.SearchHit;
import com.dk.search.common.model.SearchResult;
import com.dk.search.querynode.grpc.IndexService;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SearchExecutor implements Closeable {

    private static final Logger LOGGER = Logger.getLogger(SearchExecutor.class.getName());
    private final ExecutorService shardExecutor;
    private final IndexService indexService;
    private final Duration shardTimeout = Duration.ofSeconds(2);

    public SearchExecutor(ExecutorService shardExecutor, IndexService indexService) {
        this.shardExecutor = shardExecutor;
        this.indexService = indexService;
    }

    public SearchExecutor(IndexService indexService) {
        // FIXME: temp fix thread pool size
        this(Executors.newFixedThreadPool(Math.min(2, Runtime.getRuntime().availableProcessors())), indexService);
    }

    /**
     * Global search across all shards (or a subset) with pagination.
     *
     * @param queryString user query
     * @param shardIds    shards to search; MUST be non-null and non-empty
     * @param page        zero-based page index
     * @param size        page size
     * @param topK        max hits to retrieve per shard (used to approximate global top-K)
     */
    @SuppressWarnings({"all"})
    public SearchResult search(String queryString,
                               List<String> shardIds,
                               int page,
                               int size,
                               int topK) {

        long startNanos = System.nanoTime();
        if (page < 0) page = 0;
        if (size <= 0) size = 10;
        if (shardIds == null || shardIds.isEmpty()) {
            throw new IllegalArgumentException("shardIds must not be null or empty");
        }

        // How many docs we might need globally for this page
        int requiredForPage = (page + 1) * size;
        int perShardLimit = Math.max(topK, requiredForPage);

        // One async call per shard → each returns a model.SearchResult
        List<CompletableFuture<SearchResult>> futures = new ArrayList<>();
        for (String shardId : shardIds) {
            final String sid = shardId;

            CompletableFuture<SearchResult> future =
                    CompletableFuture.supplyAsync(() -> {
                        return indexService.searchShardTopK(queryString, sid, perShardLimit);
                    }, shardExecutor);
            futures.add(future);
        }

        long totalHits = 0L;
        List<SearchHit> allHits = new ArrayList<>();
        for (CompletableFuture<SearchResult> future : futures) {
            try {
                SearchResult shardResult = future.get(shardTimeout.toMillis(), TimeUnit.MILLISECONDS);
                if (shardResult != null) {
                    totalHits += shardResult.getTotalHits();
                    allHits.addAll(shardResult.getHits());
                }
            } catch (TimeoutException te) {
                LOGGER.log(Level.WARNING, "Shard search timed out", te);
            } catch (ExecutionException ee) {
                LOGGER.log(Level.SEVERE, "Shard search failed", ee.getCause());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                LOGGER.log(Level.WARNING, "Shard search interrupted", ie);
            }
        }

        // Global sort by score descending
        allHits.sort(Comparator.comparingDouble(SearchHit::getScore).reversed());
        int fromIndex = page * size;
        int toIndex = Math.min(fromIndex + size, allHits.size());
        List<SearchHit> pageHits;

        if (fromIndex >= allHits.size()) {
            pageHits = Collections.emptyList();
        } else {
            pageHits = allHits.subList(fromIndex, toIndex);
        }

        long tookMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - startNanos);
        return new SearchResult(
                pageHits,
                totalHits,
                tookMicros,
                page
        );
    }

    @Override
    public void close() throws IOException {
        shardExecutor.shutdown();
    }

}