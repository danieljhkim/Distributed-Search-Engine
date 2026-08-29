package com.danieljhkim.dsearch.querynode.search;

import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.common.model.SearchHit;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.proto.common.*;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;
import com.danieljhkim.dsearch.querynode.grpc.BaseIndexService;
import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class SearchExecutor implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(SearchExecutor.class);
    private static final Comparator<SearchHit> COMPARABLE_SCORE_ORDER = Comparator.comparingDouble(SearchHit::getScore)
            .reversed()
            .thenComparing(SearchHit::getDocId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final ExecutorService shardExecutor;
    private final NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager;
    private final Duration shardTimeout;

    public SearchExecutor(
            ExecutorService shardExecutor,
            NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager) {
        this(shardExecutor, nodeClientManager, Duration.ofSeconds(2));
    }

    SearchExecutor(
            ExecutorService shardExecutor,
            NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager,
            Duration shardTimeout) {
        this.shardExecutor = Objects.requireNonNull(shardExecutor, "shardExecutor must not be null");
        this.nodeClientManager = Objects.requireNonNull(nodeClientManager, "nodeClientManager must not be null");
        this.shardTimeout = Objects.requireNonNull(shardTimeout, "shardTimeout must not be null");
    }

    public SearchExecutor(NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager) {
        this(Executors.newVirtualThreadPerTaskExecutor(), nodeClientManager);
    }

    public SearchResult searchHybrid(
            String queryString,
            String shardId,
            int page,
            int size,
            BaseIndexService indexService,
            FusionStrategy fusionStrategy) {
        return searchHybrid(queryString, shardId, page, size, indexService, fusionStrategy, null, false, null);
    }

    public SearchResult searchHybrid(
            String queryString,
            String shardId,
            int page,
            int size,
            BaseIndexService indexService,
            FusionStrategy fusionStrategy,
            List<Filter> filters,
            boolean highlight,
            List<FacetRequest> facetRequests) {
        int fetchSize = size * (page + 1);
        SearchResult bm25Result = search(
                queryString, shardId, 0, fetchSize, SearchType.BM25, indexService, filters, highlight, facetRequests);
        SearchResult semanticResult = search(
                queryString,
                shardId,
                0,
                fetchSize,
                SearchType.SEMANTIC,
                indexService,
                filters,
                highlight,
                facetRequests);

        List<SearchHit> res = HybridFusion.fuse(bm25Result, semanticResult, fusionStrategy, fetchSize, 0.5, 0.5);

        List<SearchHit> pageHits = slicePage(res, normalizePage(page), normalizeSize(size));

        // Use facets from BM25 result (should be same as semantic since computed on
        // same filtered query)
        List<FacetResponse> facets = bm25Result.getFacets();

        return new SearchResult(
                pageHits,
                Math.max(semanticResult.getTotalHits(), bm25Result.getTotalHits()), // approx
                normalizePage(page),
                facets,
                SearchResult.FanoutMetadata.combine(
                        bm25Result.getFanoutMetadata(), semanticResult.getFanoutMetadata()));
    }

    /**
     * Global search across all index nodes for a given shardId.
     */
    public SearchResult search(
            String queryString,
            String shardId,
            int page,
            int size,
            SearchType searchType,
            BaseIndexService indexService) {
        return search(queryString, shardId, page, size, searchType, indexService, null, false, null);
    }

    /**
     * Global search across all index nodes for a given shardId with filters,
     * highlighting, and facets.
     */
    @SuppressWarnings("all")
    public SearchResult search(
            String queryString,
            String shardId,
            int page,
            int size,
            SearchType searchType,
            BaseIndexService indexService,
            List<Filter> filters,
            boolean highlight,
            List<FacetRequest> facetRequests) {

        page = normalizePage(page);
        size = normalizeSize(size);

        String requestId = MDC.get("requestId");
        int requiredForPage = (page + 1) * size;
        int perShardLimit = requiredForPage;
        Map<String, Long> nodeTimingsMs = new ConcurrentHashMap<>();

        // Fan out
        List<NodeSearchTask> futures = new ArrayList<>();
        for (String nodeId : nodeClientManager.getActiveNodeIds()) {
            futures.add(new NodeSearchTask(
                    nodeId,
                    submitNodeSearch(
                            requestId,
                            nodeId,
                            shardId,
                            queryString,
                            perShardLimit,
                            searchType,
                            indexService,
                            filters,
                            highlight,
                            facetRequests,
                            nodeTimingsMs)));
        }

        long deadlineNanos = System.nanoTime() + shardTimeout.toNanos();

        // Join + merge
        MergeAccumulator acc = awaitAndMerge(futures, deadlineNanos, requestId, shardId, searchType);

        // best-effort cancellation of any remaining futures
        cancelOutstanding(futures);

        // Global merge + page
        List<SearchHit> mergedHits = mergeHits(acc.nodeHits, searchType);
        List<SearchHit> pageHits = slicePage(mergedHits, page, size);

        // Materialize facets in request order
        List<FacetResponse> aggregatedFacets = buildAggregatedFacets(facetRequests, acc.facetAggregation);

        long sumMs = nodeTimingsMs.values().stream().mapToLong(Long::longValue).sum();
        SearchResult.FanoutMetadata fanoutMetadata = new SearchResult.FanoutMetadata(
                futures.size(), acc.successfulNodes, acc.failedNodes, acc.timedOutNodes);
        logFanoutSummary(
                requestId, shardId, searchType, acc.totalHits, page, size, sumMs, nodeTimingsMs, fanoutMetadata);

        return new SearchResult(
                pageHits, acc.totalHits, page, aggregatedFacets.isEmpty() ? null : aggregatedFacets, fanoutMetadata);
    }

    private CompletableFuture<SearchResult> submitNodeSearch(
            String requestId,
            String nodeId,
            String shardId,
            String queryString,
            int perShardLimit,
            SearchType searchType,
            BaseIndexService indexService,
            List<Filter> filters,
            boolean highlight,
            List<FacetRequest> facetRequests,
            Map<String, Long> nodeTimingsMs) {

        return CompletableFuture.supplyAsync(
                () -> {
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
                                searchType,
                                filters,
                                highlight,
                                facetRequests,
                                shardTimeout);
                    } finally {
                        long tookMs = (System.nanoTime() - startNanos) / 1_000_000L;
                        nodeTimingsMs.put(nodeId, tookMs);
                        LOG.info(
                                "Shard search timing: requestId={}, nodeId={}, shardId={}, searchType={}, tookMs={}",
                                requestId,
                                nodeId,
                                shardId,
                                searchType,
                                tookMs);
                        if (requestId != null) {
                            MDC.remove("requestId");
                        }
                    }
                },
                shardExecutor);
    }

    private MergeAccumulator awaitAndMerge(
            List<NodeSearchTask> futures, long deadlineNanos, String requestId, String shardId, SearchType searchType) {

        MergeAccumulator acc = new MergeAccumulator();

        if (futures.isEmpty()) {
            LOG.warn(
                    "Search fanout failed: no active index nodes; requestId={}, shardId={}, searchType={}",
                    requestId,
                    shardId,
                    searchType);
            return acc;
        }

        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos > 0) {
            CompletableFuture<?>[] allNodeFutures =
                    futures.stream().map(NodeSearchTask::future).toArray(CompletableFuture[]::new);
            try {
                CompletableFuture.allOf(allNodeFutures).get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (TimeoutException te) {
                LOG.warn(
                        "Search fanout deadline reached; requestId={}, shardId={}, searchType={}, unfinishedNodes={}",
                        requestId,
                        shardId,
                        searchType,
                        countUnfinished(futures));
            } catch (ExecutionException ee) {
                // Individual node failures are classified below so the summary remains structured.
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                LOG.warn(
                        "Search fanout interrupted while awaiting index nodes; requestId={}, shardId={}, searchType={}",
                        requestId,
                        shardId,
                        searchType);
            }
        } else {
            LOG.warn(
                    "Search fanout deadline exhausted before awaiting index nodes; requestId={}, shardId={}, searchType={}",
                    requestId,
                    shardId,
                    searchType);
        }

        for (NodeSearchTask task : futures) {
            String nodeId = task.nodeId();
            CompletableFuture<SearchResult> future = task.future();
            if (!future.isDone()) {
                acc.timedOutNodes++;
                LOG.warn(
                        "Node search timed out; requestId={}, shardId={}, searchType={}, nodeId={}",
                        requestId,
                        shardId,
                        searchType,
                        nodeId);
                continue;
            }
            try {
                SearchResult shardResult = future.join();
                acc.successfulNodes++;
                if (shardResult != null) {
                    acc.totalHits += shardResult.getTotalHits();
                    acc.nodeHits.add(new NodeHits(shardResult.getHits()));
                    aggregateFacets(acc.facetAggregation, shardResult.getFacets());
                }
            } catch (CancellationException ce) {
                acc.timedOutNodes++;
                LOG.warn(
                        "Node search cancelled after timeout; requestId={}, shardId={}, searchType={}, nodeId={}",
                        requestId,
                        shardId,
                        searchType,
                        nodeId);
            } catch (CompletionException ce) {
                acc.failedNodes++;
                Throwable cause = ce.getCause() != null ? ce.getCause() : ce;
                LOG.warn(
                        "Node search failed; requestId={}, shardId={}, searchType={}, nodeId={}, failureType={}, failureMessage={}",
                        requestId,
                        shardId,
                        searchType,
                        nodeId,
                        cause.getClass().getSimpleName(),
                        safeFailureMessage(cause));
            }
        }

        return acc;
    }

    /**
     * Merges bounded node-local result lists under the score contract for the
     * requested search type.
     *
     * <p>Semantic scores are produced by the same embedding model and remain
     * directly comparable. BM25 scores are not comparable because Lucene computes
     * term statistics from each node's local index. Distributed BM25 is therefore
     * explicitly approximate: each node's strict local BM25 order is preserved and
     * converted to a reciprocal-rank merge score. Equal local scores share a rank,
     * and all remaining ties use document id. The returned BM25 score is this
     * comparable merge score, not a single-index BM25 score.
     *
     * <p>This preserves bounded top-K fan-out and deterministic ordering without
     * claiming the exactness that would require a distributed term-statistics
     * round trip.
     */
    private static List<SearchHit> mergeHits(List<NodeHits> nodeHits, SearchType searchType) {
        if (searchType != SearchType.BM25) {
            return nodeHits.stream()
                    .flatMap(nodeResult -> nodeResult.hits().stream())
                    .filter(Objects::nonNull)
                    .sorted(COMPARABLE_SCORE_ORDER)
                    .toList();
        }

        List<SearchHit> rankedHits = new ArrayList<>();
        for (NodeHits nodeResult : nodeHits) {
            List<SearchHit> localHits = nodeResult.hits().stream()
                    .filter(Objects::nonNull)
                    .sorted(COMPARABLE_SCORE_ORDER)
                    .toList();

            int rank = 0;
            float previousScore = Float.NaN;
            for (int position = 0; position < localHits.size(); position++) {
                SearchHit hit = localHits.get(position);
                if (position == 0 || Float.compare(hit.getScore(), previousScore) != 0) {
                    rank = position;
                    previousScore = hit.getScore();
                }
                float mergeScore = 1.0f / (rank + 1.0f);
                rankedHits.add(new SearchHit(
                        hit.getDocId(),
                        hit.getTitle(),
                        hit.getContent(),
                        mergeScore,
                        hit.getHighlightedFields(),
                        hit.getFields()));
            }
        }
        rankedHits.sort(COMPARABLE_SCORE_ORDER);
        return rankedHits;
    }

    private static long countUnfinished(List<NodeSearchTask> futures) {
        return futures.stream().filter(task -> !task.future().isDone()).count();
    }

    private static String safeFailureMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? "<none>" : message;
    }

    private void cancelOutstanding(List<NodeSearchTask> futures) {
        for (NodeSearchTask task : futures) {
            CompletableFuture<SearchResult> future = task.future();
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private void aggregateFacets(Map<String, Map<String, Long>> facetAggregation, List<FacetResponse> facets) {
        if (facets == null || facets.isEmpty()) {
            return;
        }
        for (FacetResponse facetResp : facets) {
            String field = facetResp.getField();
            facetAggregation.putIfAbsent(field, new HashMap<>());
            Map<String, Long> fieldCounts = facetAggregation.get(field);
            for (FacetBucket bucket : facetResp.getBucketsList()) {
                fieldCounts.merge(bucket.getValue(), bucket.getCount(), Long::sum);
            }
        }
    }

    private List<FacetResponse> buildAggregatedFacets(
            List<FacetRequest> facetRequests, Map<String, Map<String, Long>> facetAggregation) {

        if (facetRequests == null || facetRequests.isEmpty()) {
            return Collections.emptyList();
        }

        List<FacetResponse> aggregatedFacets = new ArrayList<>(facetRequests.size());

        for (FacetRequest facetReq : facetRequests) {
            String field = facetReq.getField();
            int topN = facetReq.getSize() > 0 ? facetReq.getSize() : 10;

            Map<String, Long> fieldCounts = facetAggregation.get(field);
            FacetResponse.Builder facetBuilder = FacetResponse.newBuilder().setField(field);

            if (fieldCounts != null && !fieldCounts.isEmpty()) {
                fieldCounts.entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue()
                                .reversed()
                                .thenComparing(Map.Entry.comparingByKey()))
                        .limit(topN)
                        .forEach(e -> facetBuilder.addBuckets(FacetBucket.newBuilder()
                                .setValue(e.getKey())
                                .setCount(e.getValue())
                                .build()));
            }

            aggregatedFacets.add(facetBuilder.build());
        }

        return aggregatedFacets;
    }

    private static int normalizePage(int page) {
        return Math.max(0, page);
    }

    private static int normalizeSize(int size) {
        return size <= 0 ? 10 : size;
    }

    private static List<SearchHit> slicePage(List<SearchHit> hits, int page, int size) {
        if (hits == null || hits.isEmpty()) {
            return Collections.emptyList();
        }
        int fromIndex = page * size;
        if (fromIndex >= hits.size()) {
            return Collections.emptyList();
        }
        int toIndex = Math.min(fromIndex + size, hits.size());
        return hits.subList(fromIndex, toIndex);
    }

    private static final class MergeAccumulator {
        final List<NodeHits> nodeHits = new ArrayList<>();
        long totalHits = 0L;
        final Map<String, Map<String, Long>> facetAggregation = new HashMap<>();
        int successfulNodes = 0;
        int failedNodes = 0;
        int timedOutNodes = 0;
    }

    private void logFanoutSummary(
            String requestId,
            String shardId,
            SearchType searchType,
            long totalHits,
            int page,
            int size,
            long totalNodeTimeMs,
            Map<String, Long> nodeTimingsMs,
            SearchResult.FanoutMetadata fanoutMetadata) {
        String message =
                "Search fanout summary: requestId={}, shardId={}, searchType={}, fanoutStatus={}, totalHits={}, page={}, size={}, attemptedNodes={}, succeededNodes={}, failedNodes={}, timedOutNodes={}, totalNodeTimeMs={}, nodeTimingsMs={}";
        Object[] args = {
            requestId,
            shardId,
            searchType,
            fanoutMetadata.status(),
            totalHits,
            page,
            size,
            fanoutMetadata.attemptedNodes(),
            fanoutMetadata.succeededNodes(),
            fanoutMetadata.failedNodes(),
            fanoutMetadata.timedOutNodes(),
            totalNodeTimeMs,
            nodeTimingsMs
        };
        if (fanoutMetadata.status() == SearchResult.FanoutStatus.SUCCESS) {
            LOG.info(message, args);
        } else {
            LOG.warn(message, args);
        }
    }

    private record NodeSearchTask(String nodeId, CompletableFuture<SearchResult> future) {}

    private record NodeHits(List<SearchHit> hits) {
        private NodeHits {
            hits = hits == null ? List.of() : List.copyOf(hits);
        }
    }

    @Override
    public void close() throws IOException {
        shardExecutor.shutdown();
    }
}
