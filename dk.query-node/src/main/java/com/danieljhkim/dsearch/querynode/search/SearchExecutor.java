package com.danieljhkim.dsearch.querynode.search;

import com.danieljhkim.dsearch.common.enums.HybridFusionStrategy;
import com.danieljhkim.dsearch.common.enums.SearchType;
import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.common.model.SearchHit;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;
import com.danieljhkim.dsearch.querynode.grpc.BaseIndexService;

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
    private final NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager;
    private final Duration shardTimeout = Duration.ofSeconds(2);

    public SearchExecutor(
            ExecutorService shardExecutor,
            NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager
    ) {
        this.shardExecutor = shardExecutor;
        this.nodeClientManager = nodeClientManager;
    }

    public SearchExecutor(
            NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager
    ) {
        this(Executors.newVirtualThreadPerTaskExecutor(), nodeClientManager);
    }

    public SearchResult searchHybrid(String queryString,
                                     String shardId,
                                     int page,
                                     int size,
                                     BaseIndexService indexService
    ) {
        int fetchSize = size * (page + 1);
        // TODO: fanout search
        SearchResult bm25Result = search(
                queryString,
                shardId,
                0,
                fetchSize,
                SearchType.BM25,
                indexService
        );
        SearchResult semanticResult = search(
                queryString,
                shardId,
                0,
                fetchSize,
                SearchType.SEMANTIC,
                indexService
        );

        List<SearchHit> res = HybridFusion.fuse(
                bm25Result,
                semanticResult,
                HybridFusionStrategy.RRF,
                fetchSize,
                0.5,
                0.5
        );
        // Paginate fused result
        int fromIndex = page * size;
        int toIndex = Math.min(fromIndex + size, res.size());
        List<SearchHit> pageHits;
        if (fromIndex >= res.size()) {
            pageHits = Collections.emptyList();
        } else {
            pageHits = res.subList(fromIndex, toIndex);
        }
        return new SearchResult(
                pageHits,
                Math.max(semanticResult.getTotalHits(), bm25Result.getTotalHits()), // FIXME: approximate
                page
        );
    }

    /**
     * Global search across all shards (or a subset) with pagination.
     *
     * @param queryString user query
     * @param shardId     shards to search;
     * @param page        zero-based page index
     * @param size        page size
     * @param topK        max hits to retrieve per shard (used to approximate global top-K)
     */
    @SuppressWarnings({"all"})
    public SearchResult search(String queryString,
                               String shardId,
                               int page,
                               int size,
                               SearchType searchType,
                               BaseIndexService indexService
    ) {
        if (page < 0) page = 0;
        if (size <= 0) size = 10;

        // How many docs we might need globally for this page
        int requiredForPage = (page + 1) * size;
        int perShardLimit = requiredForPage;

        // One async call per shard → each returns a model.SearchResult
        List<CompletableFuture<SearchResult>> futures = new ArrayList<>();
        for (String nodeId : nodeClientManager.getClientMap().keySet()) {
            CompletableFuture<SearchResult> future =
                    CompletableFuture.supplyAsync(() ->
                                    indexService.searchShardTopK(queryString, nodeId, shardId, perShardLimit, searchType),
                            shardExecutor
                    );
            futures.add(future);
        }

        List<SearchHit> allHits = new ArrayList<>();
        long totalHits = 0L;
        long deadlineNanos = System.nanoTime() + shardTimeout.toNanos(); // GLOBAL timeout budget

        for (CompletableFuture<SearchResult> future : futures) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                LOGGER.log(Level.WARNING, "Global shard search timeout budget exhausted; skipping remaining shards");
                break;
            }
            try {
                SearchResult shardResult = future.get(remainingNanos, TimeUnit.NANOSECONDS);
                if (shardResult != null) {
                    totalHits += shardResult.getTotalHits();
                    allHits.addAll(shardResult.getHits());
                }
            } catch (TimeoutException te) {
                // This shard didn't finish within the remaining global budget
                LOGGER.log(Level.WARNING, "Shard search timed out before global deadline", te);
            } catch (ExecutionException ee) {
                LOGGER.log(Level.SEVERE, "Shard search failed", ee.getCause());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                LOGGER.log(Level.WARNING, "Shard search interrupted", ie);
                break;
            }
        }

        for (CompletableFuture<SearchResult> future : futures) {
            if (!future.isDone()) {
                future.cancel(true); // best-effort interruption of the shard search
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
        return new SearchResult(
                pageHits,
                totalHits,
                page
        );
    }

    @Override
    public void close() throws IOException {
        shardExecutor.shutdown();
    }

}