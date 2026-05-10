package com.danieljhkim.dsearch.querynode.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.danieljhkim.dsearch.common.enums.RoutingStrategy;
import com.danieljhkim.dsearch.common.grpc.NodeClient;
import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.common.model.SearchHit;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import com.danieljhkim.dsearch.proto.common.FacetBucket;
import com.danieljhkim.dsearch.proto.common.FacetRequest;
import com.danieljhkim.dsearch.proto.common.FacetResponse;
import com.danieljhkim.dsearch.proto.common.Filter;
import com.danieljhkim.dsearch.proto.common.FusionStrategy;
import com.danieljhkim.dsearch.proto.common.SearchType;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;
import com.danieljhkim.dsearch.querynode.grpc.BaseIndexService;
import io.grpc.ManagedChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SearchExecutorTest {

    private static final Duration TEST_TIMEOUT = Duration.ofMillis(50);

    private ExecutorService shardExecutor;

    @AfterEach
    void tearDown() {
        if (shardExecutor != null) {
            shardExecutor.shutdownNow();
        }
    }

    @Test
    void successfulFanoutAcrossMultipleNodesSortsAndPaginatesHitsWithFields() {
        RecordingIndexService indexService = new RecordingIndexService()
                .success(
                        "1",
                        result(
                                List.of(
                                        hit("doc-low", 1.0f, Map.of("source", "node-1", "rank", "4")),
                                        hit("doc-high", 9.0f, Map.of("source", "node-1", "rank", "2"))),
                                7,
                                null))
                .success(
                        "2",
                        result(
                                List.of(
                                        hit("doc-top", 10.0f, Map.of("source", "node-2", "rank", "1")),
                                        hit("doc-mid", 7.0f, Map.of("source", "node-2", "rank", "3"))),
                                5,
                                null));

        SearchResult result = searchExecutor(node("1", true), node("2", true))
                .search("coffee", "shard-a", 1, 2, SearchType.BM25, indexService);

        assertDocIds(List.of("doc-mid", "doc-low"), result);
        assertEquals(12L, result.getTotalHits());
        assertEquals(1, result.getPage());

        SearchHit firstHit = result.getHits().getFirst();
        assertEquals("title-doc-mid", firstHit.getTitle());
        assertEquals("content-doc-mid", firstHit.getContent());
        assertEquals(Map.of("source", "node-2", "rank", "3"), firstHit.getFields());
        assertEquals(Map.of("content", "highlight-doc-mid"), firstHit.getHighlightedFields());

        assertEquals(2, indexService.calls().size());
        assertTrue(indexService.calls().stream().allMatch(call -> call.searchType() == SearchType.BM25));
        assertTrue(indexService.calls().stream().allMatch(call -> call.topK() == 4));
    }

    @Test
    void successfulNodeAndFailingNodeReturnPartialMetadataAndSkipInactiveNodes() {
        RecordingIndexService indexService = new RecordingIndexService()
                .success("1", result(hit("doc-1", 2.0f)))
                .failure("2", new IllegalStateException("node down"))
                .failure("3", new IllegalStateException("inactive node should not be called"));

        SearchResult result = searchExecutor(node("1", true), node("2", true), node("3", false))
                .search("coffee", "shard-a", 0, 10, SearchType.BM25, indexService);

        assertEquals(
                List.of("doc-1"),
                result.getHits().stream().map(SearchHit::getDocId).toList());
        assertEquals(1L, result.getTotalHits());
        assertEquals(Set.of("1", "2"), Set.copyOf(indexService.calledNodes()));
        assertFalse(indexService.calledNodes().contains("3"));
        assertTrue(indexService.deadlines().stream().allMatch(TEST_TIMEOUT::equals));

        SearchResult.FanoutMetadata metadata = result.getFanoutMetadata();
        assertEquals(SearchResult.FanoutStatus.PARTIAL_FAILURE, metadata.status());
        assertEquals(2, metadata.attemptedNodes());
        assertEquals(1, metadata.succeededNodes());
        assertEquals(1, metadata.failedNodes());
        assertEquals(0, metadata.timedOutNodes());
    }

    @Test
    void timedOutNodeReturnsPartialMetadataWithoutDroppingCompletedNode() {
        RecordingIndexService indexService = new RecordingIndexService()
                .success("1", result(hit("doc-1", 5.0f)))
                .slowSuccess("2", Duration.ofMillis(250), result(hit("late-doc", 10.0f)));

        SearchResult result = searchExecutor(node("1", true), node("2", true))
                .search("coffee", "shard-a", 0, 10, SearchType.BM25, indexService);

        assertEquals(
                List.of("doc-1"),
                result.getHits().stream().map(SearchHit::getDocId).toList());
        assertEquals(Set.of("1", "2"), Set.copyOf(indexService.calledNodes()));

        SearchResult.FanoutMetadata metadata = result.getFanoutMetadata();
        assertEquals(SearchResult.FanoutStatus.PARTIAL_FAILURE, metadata.status());
        assertEquals(2, metadata.attemptedNodes());
        assertEquals(1, metadata.succeededNodes());
        assertEquals(0, metadata.failedNodes());
        assertEquals(1, metadata.timedOutNodes());
    }

    @Test
    void allNodesUnavailableSkipsFanoutAndReturnsFailedMetadata() {
        RecordingIndexService indexService = new RecordingIndexService()
                .failure("1", new IllegalStateException("inactive node should not be called"))
                .failure("2", new IllegalStateException("inactive node should not be called"));

        SearchResult result = searchExecutor(node("1", false), node("2", false))
                .search("coffee", "shard-a", 0, 10, SearchType.BM25, indexService);

        assertEquals(List.of(), result.getHits());
        assertEquals(0L, result.getTotalHits());
        assertEquals(List.of(), indexService.calledNodes());

        SearchResult.FanoutMetadata metadata = result.getFanoutMetadata();
        assertEquals(SearchResult.FanoutStatus.FAILED, metadata.status());
        assertEquals(0, metadata.attemptedNodes());
        assertEquals(0, metadata.succeededNodes());
        assertEquals(0, metadata.failedNodes());
        assertEquals(0, metadata.timedOutNodes());
    }

    @Test
    void facetsAggregateAcrossSuccessfulNodes() {
        RecordingIndexService indexService = new RecordingIndexService()
                .success(
                        "1",
                        result(List.of(hit("doc-1", 3.0f)), List.of(facet("category", bucket("a", 2), bucket("b", 1)))))
                .success(
                        "2",
                        result(
                                List.of(hit("doc-2", 4.0f)),
                                List.of(facet("category", bucket("a", 3), bucket("c", 4)))));

        FacetRequest categoryFacet =
                FacetRequest.newBuilder().setField("category").setSize(2).build();
        SearchResult result = searchExecutor(node("1", true), node("2", true))
                .search("coffee", "shard-a", 0, 10, SearchType.BM25, indexService, null, false, List.of(categoryFacet));

        assertEquals(
                SearchResult.FanoutStatus.SUCCESS, result.getFanoutMetadata().status());
        assertEquals(2L, result.getTotalHits());
        assertEquals(1, result.getFacets().size());
        assertEquals("category", result.getFacets().getFirst().getField());
        assertEquals(
                List.of("a", "c"),
                result.getFacets().getFirst().getBucketsList().stream()
                        .map(FacetBucket::getValue)
                        .toList());
        assertEquals(Map.of("a", 5L, "c", 4L), bucketCounts(result.getFacets().getFirst()));
    }

    @Test
    void hybridSearchFusesBm25AndSemanticResultsWithDeterministicScoreOrdering() {
        RecordingIndexService indexService = new RecordingIndexService()
                .success(
                        "1",
                        SearchType.BM25,
                        result(
                                List.of(
                                        hit("doc-a", 10.0f, Map.of("source", "bm25", "node", "1")),
                                        hit("doc-c", 1.0f, Map.of("source", "bm25", "node", "1"))),
                                2,
                                null))
                .success(
                        "2",
                        SearchType.BM25,
                        result(List.of(hit("doc-b", 5.0f, Map.of("source", "bm25", "node", "2"))), 1, null))
                .success(
                        "1",
                        SearchType.SEMANTIC,
                        result(
                                List.of(
                                        hit("doc-b", 9.0f, Map.of("source", "semantic", "node", "1")),
                                        hit("doc-d", 2.0f, Map.of("source", "semantic", "node", "1"))),
                                2,
                                null))
                .success(
                        "2",
                        SearchType.SEMANTIC,
                        result(List.of(hit("doc-c", 6.0f, Map.of("source", "semantic", "node", "2"))), 1, null));

        SearchResult result = searchExecutor(node("1", true), node("2", true))
                .searchHybrid("coffee", "shard-a", 0, 3, indexService, FusionStrategy.WEIGHTED);

        assertDocIds(List.of("doc-b", "doc-a", "doc-c"), result);
        assertEquals(3L, result.getTotalHits());
        assertEquals("bm25", result.getHits().getFirst().getFields().get("source"));
        assertTrue(result.getHits().get(0).getScore() > result.getHits().get(1).getScore());
        assertTrue(result.getHits().get(1).getScore() > result.getHits().get(2).getScore());

        assertEquals(4, indexService.calls().size());
        assertEquals(
                2,
                indexService.calls().stream()
                        .filter(call -> call.searchType() == SearchType.BM25)
                        .count());
        assertEquals(
                2,
                indexService.calls().stream()
                        .filter(call -> call.searchType() == SearchType.SEMANTIC)
                        .count());
        assertTrue(indexService.calls().stream().allMatch(call -> call.topK() == 3));
    }

    private SearchExecutor searchExecutor(NodeSpec... nodes) {
        shardExecutor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "search-executor-test");
            thread.setDaemon(true);
            return thread;
        });
        return new SearchExecutor(shardExecutor, manager(nodes), TEST_TIMEOUT);
    }

    private static NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> manager(NodeSpec... specs) {
        Map<String, NodeClient<IndexServiceGrpc.IndexServiceBlockingStub>> clients = new HashMap<>();
        for (NodeSpec spec : specs) {
            clients.put(spec.nodeId(), nodeClient(spec.nodeId(), spec.active()));
        }
        return new NodeClientManager<>(
                clients, RoutingStrategy.ROUND_ROBIN, NodeRole.NODE_ROLE_INDEX, IndexServiceGrpc::newBlockingStub);
    }

    private static NodeClient<IndexServiceGrpc.IndexServiceBlockingStub> nodeClient(String nodeId, boolean active) {
        ManagedChannel channel = mock(ManagedChannel.class);
        when(channel.isShutdown()).thenReturn(false);
        when(channel.isTerminated()).thenReturn(false);
        when(channel.shutdown()).thenReturn(channel);
        NodeClient<IndexServiceGrpc.IndexServiceBlockingStub> client =
                new NodeClient<>(nodeId, IndexServiceGrpc.newBlockingStub(channel), channel, "localhost", 5000);
        client.setActive(active);
        return client;
    }

    private static SearchHit hit(String docId, float score) {
        return new SearchHit(docId, "title-" + docId, "content-" + docId, score);
    }

    private static SearchHit hit(String docId, float score, Map<String, String> fields) {
        return new SearchHit(
                docId, "title-" + docId, "content-" + docId, score, Map.of("content", "highlight-" + docId), fields);
    }

    private static SearchResult result(SearchHit hit) {
        return result(List.of(hit), null);
    }

    private static SearchResult result(List<SearchHit> hits, List<FacetResponse> facets) {
        return new SearchResult(hits, hits.size(), 0, facets);
    }

    private static SearchResult result(List<SearchHit> hits, long totalHits, List<FacetResponse> facets) {
        return new SearchResult(hits, totalHits, 0, facets);
    }

    private static void assertDocIds(List<String> expectedDocIds, SearchResult result) {
        assertEquals(
                expectedDocIds,
                result.getHits().stream().map(SearchHit::getDocId).toList());
    }

    private static FacetResponse facet(String field, Bucket... buckets) {
        FacetResponse.Builder builder = FacetResponse.newBuilder().setField(field);
        for (Bucket bucket : buckets) {
            builder.addBuckets(FacetBucket.newBuilder()
                    .setValue(bucket.value())
                    .setCount(bucket.count())
                    .build());
        }
        return builder.build();
    }

    private static Bucket bucket(String value, long count) {
        return new Bucket(value, count);
    }

    private static Map<String, Long> bucketCounts(FacetResponse response) {
        Map<String, Long> counts = new HashMap<>();
        for (FacetBucket bucket : response.getBucketsList()) {
            counts.put(bucket.getValue(), bucket.getCount());
        }
        return counts;
    }

    private static NodeSpec node(String nodeId, boolean active) {
        return new NodeSpec(nodeId, active);
    }

    private record NodeSpec(String nodeId, boolean active) {}

    private record Bucket(String value, long count) {}

    private record RequestKey(String nodeId, SearchType searchType) {}

    private record SearchCall(
            String nodeId,
            String shardId,
            int topK,
            SearchType searchType,
            List<Filter> filters,
            boolean highlight,
            List<FacetRequest> facetRequests,
            Duration deadline) {}

    private interface NodeBehavior {
        SearchResult execute();
    }

    private static final class RecordingIndexService implements BaseIndexService {
        private final Map<String, NodeBehavior> nodeBehaviors = new ConcurrentHashMap<>();
        private final Map<RequestKey, NodeBehavior> typedBehaviors = new ConcurrentHashMap<>();
        private final List<String> calledNodes = Collections.synchronizedList(new ArrayList<>());
        private final List<Duration> deadlines = Collections.synchronizedList(new ArrayList<>());
        private final List<SearchCall> calls = Collections.synchronizedList(new ArrayList<>());

        RecordingIndexService success(String nodeId, SearchResult result) {
            nodeBehaviors.put(nodeId, () -> result);
            return this;
        }

        RecordingIndexService success(String nodeId, SearchType searchType, SearchResult result) {
            typedBehaviors.put(new RequestKey(nodeId, searchType), () -> result);
            return this;
        }

        RecordingIndexService slowSuccess(String nodeId, Duration delay, SearchResult result) {
            nodeBehaviors.put(nodeId, () -> {
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", e);
                }
                return result;
            });
            return this;
        }

        RecordingIndexService failure(String nodeId, RuntimeException failure) {
            nodeBehaviors.put(nodeId, () -> {
                throw failure;
            });
            return this;
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
                Duration deadline) {
            calledNodes.add(nodeId);
            deadlines.add(deadline);
            calls.add(new SearchCall(nodeId, shardId, topK, searchType, filters, highlight, facetRequests, deadline));
            NodeBehavior behavior = typedBehaviors.get(new RequestKey(nodeId, searchType));
            if (behavior == null) {
                behavior = nodeBehaviors.get(nodeId);
            }
            if (behavior == null) {
                throw new AssertionError("Unexpected node call: " + nodeId);
            }
            return behavior.execute();
        }

        List<String> calledNodes() {
            return List.copyOf(calledNodes);
        }

        List<Duration> deadlines() {
            return List.copyOf(deadlines);
        }

        List<SearchCall> calls() {
            return List.copyOf(calls);
        }
    }
}
