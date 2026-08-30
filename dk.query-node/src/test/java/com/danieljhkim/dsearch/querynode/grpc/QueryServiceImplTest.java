package com.danieljhkim.dsearch.querynode.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.danieljhkim.dsearch.common.model.SearchHit;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.proto.common.FacetResponse;
import com.danieljhkim.dsearch.proto.common.SearchType;
import com.danieljhkim.dsearch.proto.query.FanoutStatus;
import com.danieljhkim.dsearch.proto.query.QueryRequest;
import com.danieljhkim.dsearch.proto.query.QueryResponse;
import com.danieljhkim.dsearch.querynode.search.SearchExecutor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QueryServiceImplTest {

    @Mock
    private SearchExecutor searchExecutor;

    @Mock
    private BaseIndexService indexService;

    private QueryServiceImpl queryService;

    @BeforeEach
    void setUp() {
        queryService = new QueryServiceImpl(searchExecutor, indexService);
    }

    @Test
    void completeSuccessReturnsOkWithFanoutCounts() {
        when(searchExecutor.search(
                        eq("coffee"),
                        eq("shard-a"),
                        eq(0),
                        eq(10),
                        eq(SearchType.BM25),
                        eq(indexService),
                        anyList(),
                        anyBoolean(),
                        anyList()))
                .thenReturn(result(
                        List.of(new SearchHit("doc-1", "title", "content", 1.0f)),
                        new SearchResult.FanoutMetadata(2, 2, 0, 0)));

        RecordingObserver observer = new RecordingObserver();
        queryService.search(request(SearchType.BM25), observer);

        assertTrue(observer.completed);
        assertNull(observer.error);
        assertNotNull(observer.response);
        assertEquals(1, observer.response.getHitsCount());
        assertEquals(
                FanoutStatus.FANOUT_STATUS_SUCCESS,
                observer.response.getFanout().getStatus());
        assertEquals(2, observer.response.getFanout().getAttemptedNodes());
        assertEquals(2, observer.response.getFanout().getSucceededNodes());
        assertEquals(0, observer.response.getFanout().getFailedNodes());
        assertEquals(0, observer.response.getFanout().getTimedOutNodes());
    }

    @Test
    void oneNodeFailureReturnsPartialFanoutMetadata() {
        when(searchExecutor.search(any(), any(), anyInt(), anyInt(), any(), any(), anyList(), anyBoolean(), anyList()))
                .thenReturn(result(
                        List.of(new SearchHit("doc-1", "title", "content", 1.0f)),
                        new SearchResult.FanoutMetadata(2, 1, 1, 0)));

        RecordingObserver observer = new RecordingObserver();
        queryService.search(request(SearchType.BM25), observer);

        assertTrue(observer.completed);
        assertNull(observer.error);
        assertEquals(
                FanoutStatus.FANOUT_STATUS_PARTIAL_FAILURE,
                observer.response.getFanout().getStatus());
        assertEquals(2, observer.response.getFanout().getAttemptedNodes());
        assertEquals(1, observer.response.getFanout().getSucceededNodes());
        assertEquals(1, observer.response.getFanout().getFailedNodes());
        assertEquals(0, observer.response.getFanout().getTimedOutNodes());
        assertEquals(1, observer.response.getHitsCount());
    }

    @Test
    void oneNodeTimeoutReturnsPartialFanoutMetadata() {
        when(searchExecutor.search(any(), any(), anyInt(), anyInt(), any(), any(), anyList(), anyBoolean(), anyList()))
                .thenReturn(result(
                        List.of(new SearchHit("doc-1", "title", "content", 1.0f)),
                        new SearchResult.FanoutMetadata(2, 1, 0, 1)));

        RecordingObserver observer = new RecordingObserver();
        queryService.search(request(SearchType.BM25), observer);

        assertTrue(observer.completed);
        assertNull(observer.error);
        assertEquals(
                FanoutStatus.FANOUT_STATUS_PARTIAL_FAILURE,
                observer.response.getFanout().getStatus());
        assertEquals(2, observer.response.getFanout().getAttemptedNodes());
        assertEquals(1, observer.response.getFanout().getSucceededNodes());
        assertEquals(0, observer.response.getFanout().getFailedNodes());
        assertEquals(1, observer.response.getFanout().getTimedOutNodes());
    }

    @Test
    void zeroActiveNodesReturnsUnavailable() {
        when(searchExecutor.search(any(), any(), anyInt(), anyInt(), any(), any(), anyList(), anyBoolean(), anyList()))
                .thenReturn(result(List.of(), new SearchResult.FanoutMetadata(0, 0, 0, 0)));

        RecordingObserver observer = new RecordingObserver();
        queryService.search(request(SearchType.BM25), observer);

        assertFalse(observer.completed);
        assertNull(observer.response);
        StatusRuntimeException error = (StatusRuntimeException) observer.error;
        assertEquals(Status.Code.UNAVAILABLE, error.getStatus().getCode());
        assertEquals(
                "Search fanout failed: attemptedNodes=0 succeededNodes=0 failedNodes=0 timedOutNodes=0",
                error.getStatus().getDescription());
    }

    @Test
    void allAttemptedNodesFailReturnsUnavailable() {
        when(searchExecutor.search(any(), any(), anyInt(), anyInt(), any(), any(), anyList(), anyBoolean(), anyList()))
                .thenReturn(result(List.of(), new SearchResult.FanoutMetadata(2, 0, 2, 0)));

        RecordingObserver observer = new RecordingObserver();
        queryService.search(request(SearchType.BM25), observer);

        assertFalse(observer.completed);
        assertNull(observer.response);
        StatusRuntimeException error = (StatusRuntimeException) observer.error;
        assertEquals(Status.Code.UNAVAILABLE, error.getStatus().getCode());
        assertEquals(
                "Search fanout failed: attemptedNodes=2 succeededNodes=0 failedNodes=2 timedOutNodes=0",
                error.getStatus().getDescription());
    }

    @Test
    void allAttemptedNodesTimeoutReturnsDeadlineExceeded() {
        when(searchExecutor.search(any(), any(), anyInt(), anyInt(), any(), any(), anyList(), anyBoolean(), anyList()))
                .thenReturn(result(List.of(), new SearchResult.FanoutMetadata(2, 0, 0, 2)));

        RecordingObserver observer = new RecordingObserver();
        queryService.search(request(SearchType.BM25), observer);

        assertFalse(observer.completed);
        assertNull(observer.response);
        StatusRuntimeException error = (StatusRuntimeException) observer.error;
        assertEquals(Status.Code.DEADLINE_EXCEEDED, error.getStatus().getCode());
        assertEquals(
                "Search fanout failed: attemptedNodes=2 succeededNodes=0 failedNodes=0 timedOutNodes=2",
                error.getStatus().getDescription());
    }

    @Test
    void requestLimitValidationRejectsOversizedRequestsBeforeFanout() {
        QueryRequest oversized =
                request(SearchType.BM25).toBuilder().setSize(1001).build();
        RecordingObserver observer = new RecordingObserver();

        queryService.search(oversized, observer);
        StatusRuntimeException error = assertInstanceOf(StatusRuntimeException.class, observer.error);
        assertEquals(Status.Code.INVALID_ARGUMENT, error.getStatus().getCode());
        assertTrue(error.getStatus().getDescription().contains("Requested pageSize"));
        assertNull(observer.response);
        assertFalse(observer.completed);
        verifyNoInteractions(searchExecutor, indexService);
    }

    @Test
    void hybridSearchForwardsStrategyAndTranslatesOptionalHitFieldsAndFacets() {
        FacetResponse facet = FacetResponse.newBuilder().setField("category").build();
        when(searchExecutor.searchHybrid(
                        eq("coffee"),
                        eq("shard-a"),
                        eq(0),
                        eq(10),
                        eq(indexService),
                        eq(com.danieljhkim.dsearch.proto.common.FusionStrategy.RRF),
                        anyList(),
                        anyBoolean(),
                        anyList()))
                .thenReturn(new SearchResult(
                        List.of(new SearchHit(
                                "doc-1", null, null, 2.0f, Map.of("content", "highlight"), Map.of("tag", "book"))),
                        1,
                        0,
                        List.of(facet),
                        new SearchResult.FanoutMetadata(1, 1, 0, 0)));

        RecordingObserver observer = new RecordingObserver();
        queryService.search(
                request(SearchType.HYBRID).toBuilder()
                        .setFusionStrategy(com.danieljhkim.dsearch.proto.common.FusionStrategy.RRF)
                        .build(),
                observer);

        assertTrue(observer.completed);
        assertNull(observer.error);
        assertEquals(1, observer.response.getHitsCount());
        assertEquals("highlight", observer.response.getHits(0).getHighlightedFieldsOrThrow("content"));
        assertEquals("book", observer.response.getHits(0).getFieldsOrThrow("tag"));
        assertEquals(List.of(facet), observer.response.getFacetsList());
    }

    @Test
    void executorFailureIsReturnedAsParseError() {
        when(searchExecutor.search(any(), any(), anyInt(), anyInt(), any(), any(), anyList(), anyBoolean(), anyList()))
                .thenThrow(new IllegalStateException("executor failed"));
        RecordingObserver observer = new RecordingObserver();

        queryService.search(request(SearchType.BM25), observer);

        assertFalse(observer.completed);
        assertTrue(observer.error instanceof com.danieljhkim.dsearch.common.exception.ParseGoneWrongException);
        assertTrue(observer.error.getMessage().contains("Failed to parse query: coffee"));
    }

    private static QueryRequest request(SearchType searchType) {
        return QueryRequest.newBuilder()
                .setQueryString("coffee")
                .setPartitionId("shard-a")
                .setPage(0)
                .setSize(10)
                .setSearchType(searchType)
                .build();
    }

    private static SearchResult result(List<SearchHit> hits, SearchResult.FanoutMetadata fanoutMetadata) {
        return new SearchResult(hits, hits.size(), 0, null, fanoutMetadata);
    }

    private static final class RecordingObserver implements StreamObserver<QueryResponse> {
        private QueryResponse response;
        private Throwable error;
        private boolean completed;

        @Override
        public void onNext(QueryResponse value) {
            this.response = value;
        }

        @Override
        public void onError(Throwable t) {
            this.error = t;
        }

        @Override
        public void onCompleted() {
            this.completed = true;
        }
    }
}
