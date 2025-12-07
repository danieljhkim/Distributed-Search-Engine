package com.danieljhkim.dsearch.querynode.search;

import com.danieljhkim.dsearch.common.enums.HybridFusionStrategy;
import com.danieljhkim.dsearch.common.enums.SearchType;
import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.common.model.SearchHit;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;
import com.danieljhkim.dsearch.querynode.grpc.BaseIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public class SearchExecutor implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(SearchExecutor.class);

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

        int fromIndex = page * size;
        int toIndex = Math.min(fromIndex + size, res.size());
        List<SearchHit> pageHits =
                (fromIndex >= res.size()) ? Collections.emptyList() : res.subList(fromIndex, toIndex);

        return new SearchResult(
                pageHits,
                Math.max(semanticResult.getTotalHits(), bm25Result.getTotalHits()), // approx
                page
        );
    }

    /**
     * Global search across all index nodes for a given shardId.
     */
    @SuppressWarnings("all")
    public SearchResult search(String queryString,
                               String shardId,
                               int page,
                               int size,
                               SearchType searchType,
                               BaseIndexService indexService
    ) {
        if (page < 0) page = 0;
        if (size <= 0) size = 10;

        String requestId = MDC.get("requestId"); // set by gRPC server interceptor

        int requiredForPage = (page + 1) * size;
        int perShardLimit = requiredForPage;

        // per-node timing (for this shard)
        Map<String, Long> nodeTimingsMs = new ConcurrentHashMap<>();

        // One async call per index node
        List<Map.Entry<String, CompletableFuture<SearchResult>>> futures = new ArrayList<>();
        for (String nodeId : nodeClientManager.getClientMap().keySet()) {
            // capture nodeId & requestId for the lambda
            CompletableFuture<SearchResult> future =
                    CompletableFuture.supplyAsync(() -> {
                                // ensure MDC propagation into this async task
                                if (requestId != null) {
                                    MDC.put("requestId", requestId);
                                }
                                long startNanos = System.nanoTime();
                                try {
                                    return indexService.searchShardTopK(
                                            queryString,
                                            nodeId,
                                            shardId,
                                            perShardLimit,
                                            searchType
                                    );
                                } finally {
                                    long tookMs = (System.nanoTime() - startNanos) / 1_000_000L;
                                    nodeTimingsMs.put(nodeId, tookMs);
                                    LOG.info("Shard search timing: requestId={}, nodeId={}, shardId={}, searchType={}, tookMs={}",
                                            requestId, nodeId, shardId, searchType, tookMs);
                                    if (requestId != null) {
                                        MDC.remove("requestId");
                                    }
                                }
                            },
                            shardExecutor
                    );
            futures.add(new AbstractMap.SimpleEntry<>(nodeId, future));
        }

        List<SearchHit> allHits = new ArrayList<>();
        long totalHits = 0L;
        long deadlineNanos = System.nanoTime() + shardTimeout.toNanos(); // global timeout budget

        for (Map.Entry<String, CompletableFuture<SearchResult>> entry : futures) {
            CompletableFuture<SearchResult> future = entry.getValue();
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                LOG.warn("Global shard search timeout budget exhausted; skipping remaining nodes; requestId={}, shardId={}, searchType={}",
                        requestId, shardId, searchType);
                break;
            }
            try {
                SearchResult shardResult = future.get(remainingNanos, TimeUnit.NANOSECONDS);
                if (shardResult != null) {
                    totalHits += shardResult.getTotalHits();
                    allHits.addAll(shardResult.getHits());
                }
            } catch (TimeoutException te) {
                LOG.warn("Node search timed out before global deadline; requestId={}, shardId={}, searchType={}",
                        requestId, shardId, searchType, te);
            } catch (ExecutionException ee) {
                LOG.error("Node search failed; requestId={}, shardId={}, searchType={}",
                        requestId, shardId, searchType, ee.getCause());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                LOG.warn("Node search interrupted; requestId={}, shardId={}, searchType={}",
                        requestId, shardId, searchType, ie);
                break;
            }
        }

        // best-effort cancellation of any remaining futures
        for (Map.Entry<String, CompletableFuture<SearchResult>> entry : futures) {
            CompletableFuture<SearchResult> future = entry.getValue();
            if (!future.isDone()) {
                future.cancel(true);
            }
        }

        // Global sort by score descending
        allHits.sort(Comparator.comparingDouble(SearchHit::getScore).reversed());
        int fromIndex = page * size;
        int toIndex = Math.min(fromIndex + size, allHits.size());

        List<SearchHit> pageHits =
                (fromIndex >= allHits.size()) ? Collections.emptyList() : allHits.subList(fromIndex, toIndex);

        // log a summary per request
        long sumMs = nodeTimingsMs.values().stream().mapToLong(Long::longValue).sum();
        LOG.info("Search fanout summary: requestId={}, shardId={}, searchType={}, totalHits={}, page={}, size={}, totalNodeTimeMs={}, nodeTimingsMs={}",
                requestId, shardId, searchType, totalHits, page, size, sumMs, nodeTimingsMs);

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