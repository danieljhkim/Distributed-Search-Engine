package com.danieljhkim.dsearch.querynode.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.enums.FieldType;
import com.danieljhkim.dsearch.common.model.SearchHit;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.common.pagination.SortOptions;
import com.danieljhkim.dsearch.common.pagination.SortValues;
import com.danieljhkim.dsearch.common.schema.AnalyzerConfig;
import com.danieljhkim.dsearch.common.schema.EmbeddingModelIdentity;
import com.danieljhkim.dsearch.common.schema.FieldSchema;
import com.danieljhkim.dsearch.common.schema.IndexSchema;
import com.danieljhkim.dsearch.proto.common.SearchType;
import com.danieljhkim.dsearch.proto.common.SortField;
import com.danieljhkim.dsearch.proto.common.SortOrder;
import com.danieljhkim.dsearch.proto.common.SortValue;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Cursor issuance and refusal at the public query boundary.
 *
 * <p>The refusals matter as much as the happy path: a cursor that no longer describes the result
 * set it was issued for has to fail loudly, because the alternative is a page that looks ordinary
 * and contains the wrong documents.
 */
@ExtendWith(MockitoExtension.class)
class QueryServiceCursorTest {

    private static final IndexSchema SCHEMA = IndexSchema.current(
            AnalyzerConfig.standard(),
            List.of(new FieldSchema("price", FieldType.DOUBLE, true, true, false, false, "standard")),
            EmbeddingModelIdentity.of("model-a", "PyTorch", 8));
    private static final long GENERATION = 4L;

    @Mock
    private SearchExecutor searchExecutor;

    @Mock
    private BaseIndexService indexService;

    @Captor
    private ArgumentCaptor<SortOptions> sortOptionsCaptor;

    private QueryServiceImpl queryService;

    @BeforeEach
    void setUp() {
        AppConfig.PaginationConfig pagination = new AppConfig.PaginationConfig();
        pagination.setCursorSigningKey("shared-test-key");
        queryService = new QueryServiceImpl(
                searchExecutor, indexService, new AppConfig.RequestLimitsConfig(), null, pagination);
    }

    @Test
    void aFullSortedPageReturnsACursorThatResumesAfterItsLastHit() {
        snapshot(GENERATION);
        stubSearch(page(hit("doc-a", 1.0), hit("doc-b", 2.0)), 25);

        QueryResponse first = search(sortedRequest(SearchType.BM25, 2));

        assertFalse(first.getNextCursor().isEmpty(), "a full page must offer a way to continue");

        // Feed the cursor straight back: it must decode and reach the executor as a resume point.
        stubSearch(page(hit("doc-c", 3.0)), 25);
        QueryResponse second = search(sortedRequest(SearchType.BM25, 2).toBuilder()
                .setCursor(first.getNextCursor())
                .build());

        SortOptions resumed = lastSortOptions();
        assertTrue(resumed.hasSearchAfter());
        assertEquals(
                List.of(SortValues.of(2.0), SortValues.of("doc-b")),
                resumed.searchAfter(),
                "the resume point must be the last hit already delivered");
        assertEquals(1, second.getHitsCount());
    }

    @Test
    void aResumedPageReportsTheTotalTheTraversalStartedWith() {
        snapshot(GENERATION);
        stubSearch(page(hit("doc-a", 1.0), hit("doc-b", 2.0)), 25);
        String cursor = search(sortedRequest(SearchType.BM25, 2)).getNextCursor();

        // A concurrent write changed what the nodes now report; the denominator must not jitter
        // underneath a client that is part-way through paging.
        stubSearch(page(hit("doc-c", 3.0)), 999);
        QueryResponse resumed = search(
                sortedRequest(SearchType.BM25, 2).toBuilder().setCursor(cursor).build());

        assertEquals(25, resumed.getTotalHits());
    }

    @Test
    void aShortPageEndsTheTraversalWithoutACursor() {
        snapshot(GENERATION);
        stubSearch(page(hit("doc-a", 1.0)), 1);

        assertTrue(search(sortedRequest(SearchType.BM25, 10)).getNextCursor().isEmpty());
    }

    @Test
    void anUnsortedRequestGetsNoCursorAndCostsNoSchemaLookup() {
        stubSearch(page(), 0);

        QueryResponse response = search(QueryRequest.newBuilder()
                .setQueryString("coffee")
                .setPartitionId("shard-a")
                .setSize(10)
                .setSearchType(SearchType.BM25)
                .build());

        assertTrue(response.getNextCursor().isEmpty());
        verifyNoInteractions(indexService);
    }

    @Test
    void sortedHitsCarryTheirSortValuesToTheClient() {
        snapshot(GENERATION);
        stubSearch(page(hit("doc-a", 1.5)), 1);

        QueryResponse response = search(sortedRequest(SearchType.BM25, 10));

        assertEquals(2, response.getHits(0).getSortValuesCount());
        assertEquals(1.5, response.getHits(0).getSortValues(0).getDoubleValue());
        assertEquals("doc-a", response.getHits(0).getSortValues(1).getStringValue());
    }

    @Test
    void aTamperedCursorIsRefused() {
        snapshot(GENERATION);
        stubSearch(page(hit("doc-a", 1.0), hit("doc-b", 2.0)), 25);
        String cursor = search(sortedRequest(SearchType.BM25, 2)).getNextCursor();

        String tampered = cursor.substring(0, cursor.length() - 2) + "AA";
        assertError(Status.Code.INVALID_ARGUMENT, "signature", withCursor(sortedRequest(SearchType.BM25, 2), tampered));
    }

    @Test
    void aMalformedCursorIsRefused() {
        snapshot(GENERATION);

        assertError(
                Status.Code.INVALID_ARGUMENT,
                "well-formed",
                withCursor(sortedRequest(SearchType.BM25, 2), "not-a-cursor"));
    }

    @Test
    void aCursorFromADifferentQueryIsRefused() {
        snapshot(GENERATION);
        stubSearch(page(hit("doc-a", 1.0), hit("doc-b", 2.0)), 25);
        String cursor = search(sortedRequest(SearchType.BM25, 2)).getNextCursor();

        QueryRequest changedQuery = withCursor(sortedRequest(SearchType.BM25, 2), cursor).toBuilder()
                .setQueryString("tea")
                .build();

        assertError(Status.Code.INVALID_ARGUMENT, "changed since the cursor was issued", changedQuery);
    }

    @Test
    void aCursorFromADifferentSortIsRefused() {
        snapshot(GENERATION);
        stubSearch(page(hit("doc-a", 1.0), hit("doc-b", 2.0)), 25);
        String cursor = search(sortedRequest(SearchType.BM25, 2)).getNextCursor();

        QueryRequest flippedSort = QueryRequest.newBuilder()
                .setQueryString("coffee")
                .setPartitionId("shard-a")
                .setSize(2)
                .setSearchType(SearchType.BM25)
                .addSort(SortField.newBuilder().setField("price").setOrder(SortOrder.SORT_ORDER_DESC))
                .setCursor(cursor)
                .build();

        assertError(Status.Code.INVALID_ARGUMENT, "changed since the cursor was issued", flippedSort);
    }

    @Test
    void aCursorIssuedAgainstAnEarlierIndexGenerationIsRefusedAsAPreconditionFailure() {
        snapshot(GENERATION);
        stubSearch(page(hit("doc-a", 1.0), hit("doc-b", 2.0)), 25);
        String cursor = search(sortedRequest(SearchType.BM25, 2)).getNextCursor();

        // An alias swap or rollback moved the partition to a different physical index.
        snapshot(GENERATION + 1);
        assertError(
                Status.Code.FAILED_PRECONDITION, "generation", withCursor(sortedRequest(SearchType.BM25, 2), cursor));
    }

    @Test
    void semanticSearchCannotBeTraversedWithACursor() {
        snapshot(GENERATION);

        assertError(
                Status.Code.INVALID_ARGUMENT,
                "nearest-neighbour candidate pool",
                withCursor(sortedRequest(SearchType.SEMANTIC, 2), "v1.aaa.bbb"));
    }

    @Test
    void hybridSearchCannotBeTraversedWithACursor() {
        snapshot(GENERATION);

        assertError(
                Status.Code.INVALID_ARGUMENT,
                "nearest-neighbour candidate pool",
                withCursor(sortedRequest(SearchType.HYBRID, 2), "v1.aaa.bbb"));
    }

    @Test
    void scoreOrderingCannotBeTraversedWithACursorBecauseScoresAreNodeLocal() {
        snapshot(GENERATION);

        QueryRequest byScore = QueryRequest.newBuilder()
                .setQueryString("coffee")
                .setPartitionId("shard-a")
                .setSize(2)
                .setSearchType(SearchType.BM25)
                .addSort(SortField.newBuilder().setField("_score").setOrder(SortOrder.SORT_ORDER_DESC))
                .setCursor("v1.aaa.bbb")
                .build();

        assertError(Status.Code.INVALID_ARGUMENT, "not comparable across nodes", byScore);
    }

    @Test
    void scoreOrderingIsStillAllowedWithoutACursor() {
        snapshot(GENERATION);
        stubSearch(page(hit("doc-a", 1.0)), 1);

        QueryResponse response = search(QueryRequest.newBuilder()
                .setQueryString("coffee")
                .setPartitionId("shard-a")
                .setSize(2)
                .setSearchType(SearchType.BM25)
                .addSort(SortField.newBuilder().setField("_score").setOrder(SortOrder.SORT_ORDER_DESC))
                .build());

        assertTrue(response.getNextCursor().isEmpty(), "an unresumable ordering must not offer a cursor");
        assertEquals(1, response.getHitsCount());
    }

    @Test
    void anIneligibleSortFieldIsRejected() {
        snapshot(GENERATION);

        QueryRequest byUnknownField = QueryRequest.newBuilder()
                .setQueryString("coffee")
                .setPartitionId("shard-a")
                .setSize(2)
                .setSearchType(SearchType.BM25)
                .addSort(SortField.newBuilder().setField("nope").setOrder(SortOrder.SORT_ORDER_ASC))
                .build();

        assertError(Status.Code.INVALID_ARGUMENT, "Unknown sort field", byUnknownField);
    }

    // ---------- helpers ----------

    private void snapshot(long generation) {
        when(indexService.inspectIndexSnapshot("shard-a"))
                .thenReturn(new BaseIndexService.IndexSnapshot(SCHEMA, generation));
    }

    private void stubSearch(List<SearchHit> hits, long totalHits) {
        when(searchExecutor.search(
                        any(),
                        any(),
                        anyInt(),
                        anyInt(),
                        any(),
                        any(),
                        anyList(),
                        anyBoolean(),
                        anyList(),
                        sortOptionsCaptor.capture()))
                .thenReturn(new SearchResult(hits, totalHits, 0, null, new SearchResult.FanoutMetadata(1, 1, 0, 0)));
    }

    private SortOptions lastSortOptions() {
        List<SortOptions> captured = sortOptionsCaptor.getAllValues();
        return captured.get(captured.size() - 1);
    }

    private QueryResponse search(QueryRequest request) {
        RecordingObserver observer = new RecordingObserver();
        queryService.search(request, observer);
        assertTrue(observer.completed, "expected a successful response but got: " + observer.error);
        assertNotNull(observer.response);
        return observer.response;
    }

    private void assertError(Status.Code expectedCode, String expectedMessageFragment, QueryRequest request) {
        RecordingObserver observer = new RecordingObserver();
        queryService.search(request, observer);

        assertFalse(observer.completed);
        StatusRuntimeException error = assertInstanceOf(StatusRuntimeException.class, observer.error);
        assertEquals(expectedCode, error.getStatus().getCode());
        assertTrue(
                error.getStatus().getDescription().contains(expectedMessageFragment),
                "expected description to explain '" + expectedMessageFragment + "' but was: "
                        + error.getStatus().getDescription());
    }

    private static QueryRequest sortedRequest(SearchType searchType, int size) {
        return QueryRequest.newBuilder()
                .setQueryString("coffee")
                .setPartitionId("shard-a")
                .setSize(size)
                .setSearchType(searchType)
                .addSort(SortField.newBuilder().setField("price").setOrder(SortOrder.SORT_ORDER_ASC))
                .build();
    }

    private static QueryRequest withCursor(QueryRequest request, String cursor) {
        return request.toBuilder().setCursor(cursor).build();
    }

    private static List<SearchHit> page(SearchHit... hits) {
        return List.of(hits);
    }

    private static SearchHit hit(String docId, double price) {
        List<SortValue> sortValues = List.of(SortValues.of(price), SortValues.of(docId));
        return new SearchHit(docId, "title", "content", 1.0f, null, Map.of(), sortValues);
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
