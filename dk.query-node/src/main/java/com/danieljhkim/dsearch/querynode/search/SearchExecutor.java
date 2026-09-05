package com.danieljhkim.dsearch.querynode.search;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.common.model.SearchHit;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.common.pagination.SortOptions;
import com.danieljhkim.dsearch.common.validation.RequestAdmissionException;
import com.danieljhkim.dsearch.proto.common.*;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;
import com.danieljhkim.dsearch.querynode.grpc.BaseIndexService;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.Status;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class SearchExecutor implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(SearchExecutor.class);
    private static final Counter FANOUT_OUTCOMES = Counter.build()
            .name("dsearch_search_fanout_outcomes_total")
            .help("Completed search fan-outs by bounded outcome")
            .labelNames("outcome")
            .register();
    private static final Gauge FANOUT_ADMISSION_AVAILABLE = Gauge.build()
            .name("dsearch_search_fanout_admission_available")
            .help("Available fan-out admission permits")
            .register();
    private static final Comparator<SearchHit> COMPARABLE_SCORE_ORDER = Comparator.comparingDouble(SearchHit::getScore)
            .reversed()
            .thenComparing(SearchHit::getDocId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final ExecutorService shardExecutor;
    private final NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager;
    private final Duration shardTimeout;
    private final Semaphore fanoutAdmission;
    private final int maxResultWindow;
    private final int retryAfterMillis;

    public SearchExecutor(
            ExecutorService shardExecutor,
            NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager) {
        this(shardExecutor, nodeClientManager, Duration.ofSeconds(2), new AppConfig.RequestLimitsConfig());
    }

    SearchExecutor(
            ExecutorService shardExecutor,
            NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager,
            Duration shardTimeout) {
        this(shardExecutor, nodeClientManager, shardTimeout, new AppConfig.RequestLimitsConfig());
    }

    SearchExecutor(
            ExecutorService shardExecutor,
            NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager,
            Duration shardTimeout,
            AppConfig.RequestLimitsConfig requestLimits) {
        this.shardExecutor = Objects.requireNonNull(shardExecutor, "shardExecutor must not be null");
        this.nodeClientManager = Objects.requireNonNull(nodeClientManager, "nodeClientManager must not be null");
        this.shardTimeout = Objects.requireNonNull(shardTimeout, "shardTimeout must not be null");
        AppConfig.RequestLimitsConfig limits = Objects.requireNonNull(requestLimits, "requestLimits must not be null");
        this.fanoutAdmission = new Semaphore(Math.max(1, limits.getMaxConcurrentFanoutCalls()), true);
        FANOUT_ADMISSION_AVAILABLE.set(this.fanoutAdmission.availablePermits());
        this.maxResultWindow = Math.max(1, limits.getMaxResultWindow());
        this.retryAfterMillis = Math.max(1, limits.getRetryAfterMillis());
    }

    public SearchExecutor(NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager) {
        this(Executors.newVirtualThreadPerTaskExecutor(), nodeClientManager);
    }

    public SearchExecutor(
            NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager,
            AppConfig.RequestLimitsConfig requestLimits) {
        this(
                Executors.newVirtualThreadPerTaskExecutor(),
                nodeClientManager,
                Duration.ofMillis(Math.max(1, requestLimits.getRequestTimeoutMillis())),
                requestLimits);
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
        return searchHybrid(
                queryString,
                shardId,
                page,
                size,
                indexService,
                fusionStrategy,
                filters,
                highlight,
                facetRequests,
                SortOptions.NONE);
    }

    /**
     * Hybrid search under an explicit ordering.
     *
     * <p>Fusion still selects the candidate set — that is what makes a hit hybrid — but when a sort
     * is requested the fused candidates are then placed in the requested order rather than in
     * fusion-score order. Cursor pagination is refused for hybrid upstream: the candidate set comes
     * from two bounded per-node lists, so there is no total order over the partition to resume in.
     */
    public SearchResult searchHybrid(
            String queryString,
            String shardId,
            int page,
            int size,
            BaseIndexService indexService,
            FusionStrategy fusionStrategy,
            List<Filter> filters,
            boolean highlight,
            List<FacetRequest> facetRequests,
            SortOptions sortOptions) {
        return searchHybrid(
                queryString,
                shardId,
                page,
                size,
                indexService,
                fusionStrategy,
                filters,
                highlight,
                facetRequests,
                sortOptions,
                null);
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
            List<FacetRequest> facetRequests,
            SortOptions sortOptions,
            List<String> storedFields) {
        SortOptions effectiveSort = sortOptions == null ? SortOptions.NONE : sortOptions;
        int fetchSize = requiredForPage(page, size);
        SearchResult bm25Result = search(
                queryString,
                shardId,
                0,
                fetchSize,
                SearchType.BM25,
                indexService,
                filters,
                highlight,
                facetRequests,
                effectiveSort,
                storedFields);
        SearchResult semanticResult = search(
                queryString,
                shardId,
                0,
                fetchSize,
                SearchType.SEMANTIC,
                indexService,
                filters,
                highlight,
                facetRequests,
                effectiveSort,
                storedFields);

        List<SearchHit> res = HybridFusion.fuse(bm25Result, semanticResult, fusionStrategy, fetchSize, 0.5, 0.5);
        if (effectiveSort.isSorted()) {
            res = mergeSortedHits(List.of(new NodeHits(res)), effectiveSort);
        }

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
        return search(
                queryString,
                shardId,
                page,
                size,
                searchType,
                indexService,
                filters,
                highlight,
                facetRequests,
                SortOptions.NONE);
    }

    /**
     * Global search across all index nodes under an explicit ordering.
     *
     * <p>The per-node fan-out size is where cursor pagination earns its keep. Offset paging must
     * ask every node for {@code page * size + size} hits, because any node could own the whole
     * page; a cursor pins an exact position in a total order, so {@code size} hits per node is
     * provably enough — no node can contribute more than {@code size} hits to the next page.
     */
    public SearchResult search(
            String queryString,
            String shardId,
            int page,
            int size,
            SearchType searchType,
            BaseIndexService indexService,
            List<Filter> filters,
            boolean highlight,
            List<FacetRequest> facetRequests,
            SortOptions sortOptions) {
        return search(
                queryString,
                shardId,
                page,
                size,
                searchType,
                indexService,
                filters,
                highlight,
                facetRequests,
                sortOptions,
                null);
    }

    public SearchResult search(
            String queryString,
            String shardId,
            int page,
            int size,
            SearchType searchType,
            BaseIndexService indexService,
            List<Filter> filters,
            boolean highlight,
            List<FacetRequest> facetRequests,
            SortOptions sortOptions,
            List<String> storedFields) {

        page = normalizePage(page);
        size = normalizeSize(size);
        SortOptions effectiveSort = sortOptions == null ? SortOptions.NONE : sortOptions;

        String requestId = MDC.get("requestId");
        int perShardLimit = effectiveSort.hasSearchAfter() ? size : requiredForPage(page, size);
        Map<String, Long> nodeTimingsMs = new ConcurrentHashMap<>();
        Context requestContext = Context.current();
        Duration remainingBudget = remainingBudget(requestContext);

        // Fan out
        List<NodeSearchTask> futures = new ArrayList<>();
        List<String> activeNodeIds = nodeClientManager.getActiveNodeIds();
        int acquiredPermits = activeNodeIds.size();
        if (acquiredPermits > 0 && !fanoutAdmission.tryAcquire(acquiredPermits)) {
            FANOUT_OUTCOMES.labels("rejected").inc();
            throw new RequestAdmissionException("search fan-out", retryAfterMillis);
        }
        FANOUT_ADMISSION_AVAILABLE.set(fanoutAdmission.availablePermits());
        int submitted = 0;
        try {
            for (String nodeId : activeNodeIds) {
                futures.add(new NodeSearchTask(
                        nodeId,
                        submitNodeSearch(
                                requestContext,
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
                                remainingBudget,
                                nodeTimingsMs,
                                effectiveSort,
                                storedFields)));
                submitted++;
            }
        } catch (RuntimeException e) {
            fanoutAdmission.release(acquiredPermits - submitted);
            FANOUT_ADMISSION_AVAILABLE.set(fanoutAdmission.availablePermits());
            cancelOutstanding(futures);
            throw e;
        }

        long deadlineNanos = saturatedAdd(System.nanoTime(), remainingBudget.toNanos());
        Context.CancellationListener cancellationListener = ignored -> cancelOutstanding(futures);
        requestContext.addListener(cancellationListener, Runnable::run);

        MergeAccumulator acc;
        try {
            // Join + merge
            acc = awaitAndMerge(futures, deadlineNanos, requestId, shardId, searchType);
        } finally {
            requestContext.removeListener(cancellationListener);
            // Cancelling the gRPC context aborts inherited downstream calls as well.
            cancelOutstanding(futures);
        }
        if (requestContext.isCancelled()) {
            Deadline contextDeadline = requestContext.getDeadline();
            Status status = contextDeadline != null && contextDeadline.isExpired()
                    ? Status.DEADLINE_EXCEEDED.withDescription("Search request deadline expired")
                    : Status.CANCELLED.withDescription("Search request was cancelled");
            throw status.withCause(requestContext.cancellationCause()).asRuntimeException();
        }

        // Global merge + page
        List<SearchHit> mergedHits = effectiveSort.isSorted()
                ? mergeSortedHits(acc.nodeHits, effectiveSort)
                : mergeHits(acc.nodeHits, searchType);
        // A resumed page starts at the cursor, so it is already the head of the merged run; only
        // offset paging still has an offset to skip.
        List<SearchHit> pageHits =
                effectiveSort.hasSearchAfter() ? takeFirst(mergedHits, size) : slicePage(mergedHits, page, size);

        // Materialize facets in request order
        List<FacetResponse> aggregatedFacets = buildAggregatedFacets(facetRequests, acc.facetAggregation);

        long sumMs = nodeTimingsMs.values().stream().mapToLong(Long::longValue).sum();
        SearchResult.FanoutMetadata fanoutMetadata = new SearchResult.FanoutMetadata(
                futures.size(), acc.successfulNodes, acc.failedNodes, acc.timedOutNodes);
        FANOUT_OUTCOMES.labels(fanoutOutcome(fanoutMetadata)).inc();
        logFanoutSummary(
                requestId, shardId, searchType, acc.totalHits, page, size, sumMs, nodeTimingsMs, fanoutMetadata);

        return new SearchResult(
                pageHits, acc.totalHits, page, aggregatedFacets.isEmpty() ? null : aggregatedFacets, fanoutMetadata);
    }

    private static String fanoutOutcome(SearchResult.FanoutMetadata metadata) {
        if (metadata.attemptedNodes() == 0 || metadata.succeededNodes() == 0) {
            return "failed";
        }
        if (metadata.timedOutNodes() > 0) {
            return "deadline_exhausted";
        }
        if (metadata.failedNodes() > 0) {
            return "partial_failure";
        }
        return "success";
    }

    private CompletableFuture<SearchResult> submitNodeSearch(
            Context requestContext,
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
            Duration deadline,
            Map<String, Long> nodeTimingsMs,
            SortOptions sortOptions,
            List<String> storedFields) {

        FanoutPermit permit = new FanoutPermit();
        CompletableFuture<SearchResult> future = CompletableFuture.supplyAsync(
                () -> {
                    if (!permit.start()) {
                        throw new CancellationException("Fan-out call was cancelled before execution");
                    }
                    Context previous = requestContext.attach();
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
                                deadline,
                                sortOptions,
                                storedFields);
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
                        requestContext.detach(previous);
                        permit.release();
                    }
                },
                shardExecutor);
        future.whenComplete((ignoredResult, ignoredFailure) -> permit.releaseIfNotStarted());
        return future;
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

    /**
     * Merges node-local runs under an explicit ordering.
     *
     * <p>Each node already applied the same effective sort, so this is a merge of sorted runs and
     * the comparator is the one thing that must not drift: {@link SortOptions#spec()} compares the
     * sort tuples the nodes reported rather than re-deriving anything locally. Because the spec
     * ends in the unique document id, equal sort values across nodes still resolve to one stable
     * order, which is what lets a cursor resume without repeating or dropping a tied hit.
     *
     * <p>Hits missing a sort tuple — an older node, or a partial response — are kept and ordered
     * last by the tuple comparator rather than dropped, so a degraded node loses ranking quality
     * instead of losing results.
     */
    private static List<SearchHit> mergeSortedHits(List<NodeHits> nodeHits, SortOptions sortOptions) {
        Comparator<SearchHit> order = Comparator.comparing(
                        SearchHit::getSortValues, sortOptions.spec().tupleComparator())
                .thenComparing(SearchHit::getDocId, Comparator.nullsLast(Comparator.naturalOrder()));
        return nodeHits.stream()
                .flatMap(nodeResult -> nodeResult.hits().stream())
                .filter(Objects::nonNull)
                .sorted(order)
                .toList();
    }

    private static List<SearchHit> takeFirst(List<SearchHit> hits, int size) {
        if (hits == null || hits.isEmpty()) {
            return Collections.emptyList();
        }
        return hits.size() <= size ? hits : hits.subList(0, size);
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

    private void aggregateFacets(Map<String, FacetAggregation> facetAggregation, List<FacetResponse> facets) {
        if (facets == null || facets.isEmpty()) {
            return;
        }
        for (FacetResponse facetResp : facets) {
            String field = facetResp.getField();
            FacetAggregation fieldAggregation =
                    facetAggregation.computeIfAbsent(field, ignored -> new FacetAggregation());
            for (FacetBucket bucket : facetResp.getBucketsList()) {
                BucketAggregation bucketAggregation =
                        fieldAggregation.buckets.computeIfAbsent(bucket.getValue(), ignored -> new BucketAggregation());
                bucketAggregation.count += bucket.getCount();
                aggregateFacets(bucketAggregation.nested, bucket.getNestedList());
            }
        }
    }

    private List<FacetResponse> buildAggregatedFacets(
            List<FacetRequest> facetRequests, Map<String, FacetAggregation> facetAggregation) {

        if (facetRequests == null || facetRequests.isEmpty()) {
            return Collections.emptyList();
        }

        List<FacetResponse> aggregatedFacets = new ArrayList<>(facetRequests.size());

        for (FacetRequest facetReq : facetRequests) {
            String field = facetReq.getField();
            int topN = facetReq.getSize() > 0 ? facetReq.getSize() : 10;

            FacetAggregation fieldAggregation = facetAggregation.get(field);
            FacetResponse.Builder facetBuilder = FacetResponse.newBuilder().setField(field);

            if (fieldAggregation != null && !fieldAggregation.buckets.isEmpty()) {
                fieldAggregation.buckets.entrySet().stream()
                        .sorted(Map.Entry.<String, BucketAggregation>comparingByValue(
                                        Comparator.comparingLong(bucket -> bucket.count))
                                .reversed()
                                .thenComparing(Map.Entry.comparingByKey()))
                        .limit(topN)
                        .forEach(e -> {
                            FacetBucket.Builder bucket = FacetBucket.newBuilder()
                                    .setValue(e.getKey())
                                    .setCount(e.getValue().count);
                            bucket.addAllNested(buildAggregatedFacets(facetReq.getNestedList(), e.getValue().nested));
                            facetBuilder.addBuckets(bucket.build());
                        });
            }

            aggregatedFacets.add(facetBuilder.build());
        }

        return aggregatedFacets;
    }

    private int requiredForPage(int page, int size) {
        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizeSize(size);
        long from = Math.multiplyExact((long) normalizedPage, normalizedSize);
        long required = Math.addExact(from, normalizedSize);
        if (required > maxResultWindow || required > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Requested result window (" + required + ") exceeds maximum allowed ("
                    + maxResultWindow + "); use cursor pagination for deeper results");
        }
        return Math.toIntExact(required);
    }

    private Duration remainingBudget(Context context) {
        if (context.isCancelled()) {
            throw Status.CANCELLED
                    .withDescription("Search request was cancelled")
                    .asRuntimeException();
        }
        Deadline deadline = context.getDeadline();
        if (deadline == null) {
            return shardTimeout;
        }
        long remainingNanos = deadline.timeRemaining(TimeUnit.NANOSECONDS);
        if (remainingNanos <= 0) {
            throw Status.DEADLINE_EXCEEDED
                    .withDescription("Search request deadline expired")
                    .asRuntimeException();
        }
        return Duration.ofNanos(Math.min(shardTimeout.toNanos(), remainingNanos));
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
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
        int fromIndex = Math.toIntExact(Math.multiplyExact((long) page, size));
        if (fromIndex >= hits.size()) {
            return Collections.emptyList();
        }
        int toIndex = (int) Math.min(Math.addExact((long) fromIndex, size), hits.size());
        return hits.subList(fromIndex, toIndex);
    }

    private static final class MergeAccumulator {
        final List<NodeHits> nodeHits = new ArrayList<>();
        long totalHits = 0L;
        final Map<String, FacetAggregation> facetAggregation = new HashMap<>();
        int successfulNodes = 0;
        int failedNodes = 0;
        int timedOutNodes = 0;
    }

    private static final class FacetAggregation {
        final Map<String, BucketAggregation> buckets = new HashMap<>();
    }

    private static final class BucketAggregation {
        long count;
        final Map<String, FacetAggregation> nested = new HashMap<>();
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

    private final class FanoutPermit {
        private static final int NOT_STARTED = 0;
        private static final int RUNNING = 1;
        private static final int RELEASED = 2;

        private final AtomicInteger state = new AtomicInteger(NOT_STARTED);

        boolean start() {
            return state.compareAndSet(NOT_STARTED, RUNNING);
        }

        void releaseIfNotStarted() {
            if (state.compareAndSet(NOT_STARTED, RELEASED)) {
                fanoutAdmission.release();
                FANOUT_ADMISSION_AVAILABLE.set(fanoutAdmission.availablePermits());
            }
        }

        void release() {
            if (state.getAndSet(RELEASED) != RELEASED) {
                fanoutAdmission.release();
                FANOUT_ADMISSION_AVAILABLE.set(fanoutAdmission.availablePermits());
            }
        }
    }

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
