package com.danieljhkim.dsearch.querynode.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.common.model.SearchHit;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.common.pagination.SortOptions;
import com.danieljhkim.dsearch.common.pagination.SortSpec;
import com.danieljhkim.dsearch.common.pagination.SortValues;
import com.danieljhkim.dsearch.common.shard.ReplicaPlacement;
import com.danieljhkim.dsearch.proto.common.FacetRequest;
import com.danieljhkim.dsearch.proto.common.Filter;
import com.danieljhkim.dsearch.proto.common.SearchType;
import com.danieljhkim.dsearch.proto.common.SortOrder;
import com.danieljhkim.dsearch.proto.common.SortValue;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;
import com.danieljhkim.dsearch.querynode.grpc.BaseIndexService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Distributed merge under an explicit ordering.
 *
 * <p>Every node applies the same effective sort locally; the query node's job is to interleave
 * those sorted runs without disturbing the total order. These cover the cases where that is easy to
 * get wrong: ties that span nodes, a node that drops out mid-request, and the fan-out size a
 * resumed page is allowed to ask for.
 */
class SearchExecutorSortMergeTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(5);
    private static final SortSpec BY_PRICE_ASC = SortSpec.effective(
            List.of(com.danieljhkim.dsearch.proto.common.SortField.newBuilder()
                    .setField("price")
                    .setOrder(SortOrder.SORT_ORDER_ASC)
                    .build()),
            false);

    private ExecutorService shardExecutor;

    @AfterEach
    void tearDown() {
        if (shardExecutor != null) {
            shardExecutor.shutdownNow();
        }
    }

    @Test
    void mergePreservesTheRequestedOrderAcrossNodes() {
        RecordingIndexService indexService = new RecordingIndexService()
                .success("1", results(sorted("a", 1.0), sorted("d", 40.0)))
                .success("2", results(sorted("b", 10.0), sorted("c", 30.0)));

        SearchResult result = execute(indexService, List.of("1", "2"), 0, 10, SortOptions.sortedBy(BY_PRICE_ASC));

        assertEquals(List.of("a", "b", "c", "d"), ids(result));
    }

    @Test
    void equalSortValuesAcrossNodesAreSeparatedDeterministicallyByDocumentId() {
        // Every hit shares one price, so only the appended _id tie-breaker orders them — and it has
        // to do so identically regardless of which node's run happened to be merged first.
        RecordingIndexService indexService = new RecordingIndexService()
                .success("1", results(sorted("doc-c", 5.0), sorted("doc-a", 5.0)))
                .success("2", results(sorted("doc-d", 5.0), sorted("doc-b", 5.0)));

        SearchResult first = execute(indexService, List.of("1", "2"), 0, 10, SortOptions.sortedBy(BY_PRICE_ASC));
        SearchResult reversedFanout =
                execute(indexService, List.of("2", "1"), 0, 10, SortOptions.sortedBy(BY_PRICE_ASC));

        assertEquals(List.of("doc-a", "doc-b", "doc-c", "doc-d"), ids(first));
        assertEquals(ids(first), ids(reversedFanout), "node ordering must not change the merged order");
    }

    @Test
    void aResumedPageAsksEachNodeForOnlyOnePage() {
        RecordingIndexService indexService = new RecordingIndexService()
                .success("1", results(sorted("a", 1.0)))
                .success("2", results());
        SortOptions resuming = new SortOptions(BY_PRICE_ASC, List.of(SortValues.of(0.5), SortValues.of("doc-0")));

        execute(indexService, List.of("1", "2"), 0, 10, resuming);

        // This is the whole point of cursor pagination: the per-node cost of page 500 is the same
        // as page 1, where offset paging would have asked for 5010 hits from every node.
        assertEquals(List.of(10, 10), indexService.requestedTopK());
    }

    @Test
    void offsetPagingStillRequestsTheWholeWindowFromEveryNode() {
        RecordingIndexService indexService = new RecordingIndexService()
                .success("1", results(sorted("a", 1.0)))
                .success("2", results());

        execute(indexService, List.of("1", "2"), 4, 10, SortOptions.sortedBy(BY_PRICE_ASC));

        assertEquals(List.of(50, 50), indexService.requestedTopK());
    }

    @Test
    void aResumedPageForwardsTheResumePointToEveryNode() {
        RecordingIndexService indexService =
                new RecordingIndexService().success("1", results()).success("2", results());
        List<SortValue> resumePoint = List.of(SortValues.of(7.5), SortValues.of("doc-7"));

        execute(indexService, List.of("1", "2"), 0, 5, new SortOptions(BY_PRICE_ASC, resumePoint));

        for (SortOptions observed : indexService.observedSortOptions()) {
            assertTrue(observed.hasSearchAfter());
            assertEquals(resumePoint, observed.searchAfter());
        }
    }

    @Test
    void aPartialFailureStillReturnsTheSurvivingNodesInOrder() {
        RecordingIndexService indexService = new RecordingIndexService()
                .success("1", results(sorted("a", 1.0), sorted("c", 30.0)))
                .failure("2", new IllegalStateException("node down"));

        SearchResult result = execute(indexService, List.of("1", "2"), 0, 10, SortOptions.sortedBy(BY_PRICE_ASC));

        assertEquals(List.of("a", "c"), ids(result));
        // The gap is reported rather than hidden: a traversal resumed across this page may omit
        // whatever the failed node would have contributed.
        assertEquals(
                SearchResult.FanoutStatus.PARTIAL_FAILURE,
                result.getFanoutMetadata().status());
        assertEquals(1, result.getFanoutMetadata().failedNodes());
    }

    @Test
    void hitsWithoutSortValuesOrderLastInsteadOfBeingDropped() {
        RecordingIndexService indexService = new RecordingIndexService()
                .success("1", results(sorted("a", 1.0)))
                .success("2", results(new SearchHit("legacy", "t", "c", 1.0f)));

        SearchResult result = execute(indexService, List.of("1", "2"), 0, 10, SortOptions.sortedBy(BY_PRICE_ASC));

        assertEquals(List.of("a", "legacy"), ids(result));
    }

    @Test
    void theMergedPageIsTruncatedToTheRequestedSize() {
        RecordingIndexService indexService = new RecordingIndexService()
                .success("1", results(sorted("a", 1.0), sorted("c", 3.0)))
                .success("2", results(sorted("b", 2.0), sorted("d", 4.0)));

        SearchResult result = execute(
                indexService,
                List.of("1", "2"),
                0,
                2,
                new SortOptions(BY_PRICE_ASC, List.of(SortValues.of(0.0), SortValues.of("doc-0"))));

        assertEquals(List.of("a", "b"), ids(result));
    }

    private SearchResult execute(
            RecordingIndexService indexService, List<String> nodeIds, int page, int size, SortOptions sortOptions) {
        indexService.reset();
        shardExecutor = Executors.newVirtualThreadPerTaskExecutor();
        try (SearchExecutor executor =
                new SearchExecutor(shardExecutor, managerWithActiveOrder(nodeIds), TEST_TIMEOUT)) {
            return executor.search(
                    "coffee",
                    "shard-a",
                    page,
                    size,
                    SearchType.BM25,
                    indexService,
                    List.of(),
                    false,
                    List.of(),
                    sortOptions);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> managerWithActiveOrder(
            List<String> nodeIds) {
        NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> manager = mock(NodeClientManager.class);
        when(manager.getActiveNodeIds()).thenReturn(nodeIds);
        when(manager.replicaReadTargets(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> nodeIds.stream()
                        .map(nodeId -> new ReplicaPlacement.ReadTarget(
                                "index/" + nodeId, nodeId, invocation.getArgument(0), false))
                        .toList());
        return manager;
    }

    private static List<String> ids(SearchResult result) {
        return result.getHits().stream().map(SearchHit::getDocId).toList();
    }

    private static SearchResult results(SearchHit... hits) {
        return new SearchResult(List.of(hits), hits.length);
    }

    private static SearchHit sorted(String docId, double price) {
        return new SearchHit(
                docId,
                "title-" + docId,
                "content-" + docId,
                1.0f,
                null,
                Map.of(),
                List.of(SortValues.of(price), SortValues.of(docId)));
    }

    private static final class RecordingIndexService implements BaseIndexService {
        private final Map<String, SearchResult> successes = new HashMap<>();
        private final Map<String, RuntimeException> failures = new HashMap<>();
        private final List<Integer> requestedTopK = Collections.synchronizedList(new ArrayList<>());
        private final List<SortOptions> observedSortOptions = Collections.synchronizedList(new ArrayList<>());

        RecordingIndexService success(String nodeId, SearchResult result) {
            successes.put(nodeId, result);
            return this;
        }

        RecordingIndexService failure(String nodeId, RuntimeException failure) {
            failures.put(nodeId, failure);
            return this;
        }

        void reset() {
            requestedTopK.clear();
            observedSortOptions.clear();
        }

        List<Integer> requestedTopK() {
            return List.copyOf(requestedTopK);
        }

        List<SortOptions> observedSortOptions() {
            return List.copyOf(observedSortOptions);
        }

        @Override
        public SearchResult search(
                String queryString, String nodeId, String shardId, int page, int size, SearchType searchType) {
            throw new UnsupportedOperationException("SearchExecutor should call the bounded top-k overload");
        }

        @Override
        public SearchResult searchShardTopK(
                String queryString,
                String nodeId,
                String shardId,
                int topK,
                SearchType searchType,
                List<Filter> filters,
                boolean highlight,
                List<FacetRequest> facetRequests,
                Duration deadline,
                SortOptions sortOptions) {
            requestedTopK.add(topK);
            observedSortOptions.add(sortOptions);
            RuntimeException failure = failures.get(nodeId);
            if (failure != null) {
                throw failure;
            }
            return successes.getOrDefault(nodeId, results());
        }
    }
}
