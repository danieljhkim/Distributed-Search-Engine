package com.danieljhkim.dsearch.indexnode.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.model.SearchDocument;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.common.validation.RequestAdmissionException;
import com.danieljhkim.dsearch.indexnode.index.IndexManager;
import com.danieljhkim.dsearch.indexnode.index.ShardIndex;
import com.danieljhkim.dsearch.ml.embedding.TextEmbedder;
import com.danieljhkim.dsearch.proto.common.FacetRequest;
import com.danieljhkim.dsearch.proto.common.Filter;
import com.danieljhkim.dsearch.proto.common.SearchType;
import com.danieljhkim.dsearch.proto.index.BulkDeleteDocumentRequest;
import com.danieljhkim.dsearch.proto.index.BulkDeleteDocumentResponse;
import com.danieljhkim.dsearch.proto.index.BulkIndexDocumentRequest;
import com.danieljhkim.dsearch.proto.index.BulkIndexDocumentResponse;
import com.danieljhkim.dsearch.proto.index.DeleteDocumentRequest;
import com.danieljhkim.dsearch.proto.index.DeleteDocumentResponse;
import com.danieljhkim.dsearch.proto.index.Document;
import com.danieljhkim.dsearch.proto.index.Field;
import com.danieljhkim.dsearch.proto.index.IndexDocumentRequest;
import com.danieljhkim.dsearch.proto.index.IndexDocumentResponse;
import com.danieljhkim.dsearch.proto.index.IndexSearchRequest;
import com.danieljhkim.dsearch.proto.index.IndexSearchResponse;
import com.danieljhkim.dsearch.proto.index.StoredFieldSelection;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexServiceImplTest {

    private static final TextEmbedder FAKE_EMBEDDER = ignored -> new float[] {1.0f, 0.0f, 0.0f};

    @TempDir
    Path tempDir;

    @Test
    void indexDocumentReturnsDurableIdAndCompletesObserver() throws IOException {
        try (IndexManager manager = manager("index")) {
            RecordingObserver<IndexDocumentResponse> observer = new RecordingObserver<>();

            new IndexServiceImpl(manager)
                    .indexDocument(
                            IndexDocumentRequest.newBuilder()
                                    .setPartitionId("0")
                                    .setDocument(document("doc-1", "durable content"))
                                    .build(),
                            observer);

            assertNull(observer.error);
            assertTrue(observer.completed);
            assertEquals("doc-1", observer.value.getId());
            assertTrue(observer.value.getSuccess());
            assertEquals(
                    1,
                    manager.searchDocument("0", "durable", 10, 0, SearchType.BM25)
                            .getTotalHits());
        }
    }

    @Test
    void indexDocumentGeneratesAnIdWhenRequestOmitsIt() throws IOException {
        try (IndexManager manager = manager("generated")) {
            RecordingObserver<IndexDocumentResponse> observer = new RecordingObserver<>();

            new IndexServiceImpl(manager)
                    .indexDocument(
                            IndexDocumentRequest.newBuilder()
                                    .setPartitionId("0")
                                    .setDocument(document("", "generated content"))
                                    .build(),
                            observer);

            assertTrue(observer.completed);
            assertFalse(observer.value.getId().isBlank());
            assertTrue(observer.value.getSuccess());
        }
    }

    @Test
    void bulkIndexReturnsOneResultPerDocumentAndCompletesEvenWhenEmpty() throws IOException {
        try (IndexManager manager = manager("bulk")) {
            IndexServiceImpl service = new IndexServiceImpl(manager);
            RecordingObserver<BulkIndexDocumentResponse> observer = new RecordingObserver<>();

            service.bulkIndexDocument(
                    BulkIndexDocumentRequest.newBuilder()
                            .setPartitionId("0")
                            .addDocuments(document("doc-1", "first content"))
                            .addDocuments(document("doc-2", "second content"))
                            .build(),
                    observer);

            assertNull(observer.error);
            assertTrue(observer.completed);
            assertTrue(observer.value.getSuccess());
            assertEquals(java.util.List.of("doc-1", "doc-2"), observer.value.getIdsList());
            assertEquals(2, observer.value.getResultsCount());
            assertTrue(observer.value.getResultsList().stream().allMatch(result -> result.getSuccess()));

            RecordingObserver<BulkIndexDocumentResponse> emptyObserver = new RecordingObserver<>();
            service.bulkIndexDocument(
                    BulkIndexDocumentRequest.newBuilder().setPartitionId("0").build(), emptyObserver);
            assertTrue(emptyObserver.completed);
            assertTrue(emptyObserver.value.getSuccess());
            assertEquals(0, emptyObserver.value.getResultsCount());
        }
    }

    @Test
    void bulkAdmissionExhaustionReturnsPartialResultsWithCommittedGeneratedIds() throws IOException {
        try (IndexManager manager = new AdmissionExhaustingManager(tempDir.resolve("bulk-admission"), 2)) {
            RecordingObserver<BulkIndexDocumentResponse> observer = new RecordingObserver<>();

            new IndexServiceImpl(manager)
                    .bulkIndexDocument(
                            BulkIndexDocumentRequest.newBuilder()
                                    .setPartitionId("0")
                                    .addDocuments(document("", "first durable content"))
                                    .addDocuments(document("", "admission exhausted content"))
                                    .addDocuments(document("doc-3", "third durable content"))
                                    .build(),
                            observer);

            assertNull(observer.error);
            assertTrue(observer.completed);
            assertFalse(observer.value.getSuccess());
            assertEquals(3, observer.value.getResultsCount());
            assertEquals(2, observer.value.getIdsCount());

            var firstResult = observer.value.getResults(0);
            assertTrue(firstResult.getSuccess());
            assertFalse(firstResult.getId().isBlank());
            assertEquals(firstResult.getId(), observer.value.getIds(0));

            var admissionResult = observer.value.getResults(1);
            assertFalse(admissionResult.getSuccess());
            assertFalse(admissionResult.getId().isBlank());
            assertTrue(admissionResult.getError().contains("retry with the returned id"));

            var thirdResult = observer.value.getResults(2);
            assertTrue(thirdResult.getSuccess());
            assertEquals("doc-3", thirdResult.getId());
            assertEquals(java.util.List.of(firstResult.getId(), "doc-3"), observer.value.getIdsList());
        }
    }

    @Test
    void bulkDeleteReturnsOneResultPerIdAndCompletesEvenWhenEmpty() throws IOException {
        try (IndexManager manager = manager("bulk-delete")) {
            IndexServiceImpl service = new IndexServiceImpl(manager);
            RecordingObserver<IndexDocumentResponse> indexed1 = new RecordingObserver<>();
            service.indexDocument(
                    IndexDocumentRequest.newBuilder()
                            .setPartitionId("0")
                            .setDocument(document("doc-1", "first content"))
                            .build(),
                    indexed1);
            RecordingObserver<IndexDocumentResponse> indexed2 = new RecordingObserver<>();
            service.indexDocument(
                    IndexDocumentRequest.newBuilder()
                            .setPartitionId("0")
                            .setDocument(document("doc-2", "second content"))
                            .build(),
                    indexed2);

            RecordingObserver<BulkDeleteDocumentResponse> observer = new RecordingObserver<>();
            service.bulkDeleteDocument(
                    BulkDeleteDocumentRequest.newBuilder()
                            .setPartitionId("0")
                            .addIds("doc-1")
                            .addIds("doc-2")
                            .build(),
                    observer);

            assertNull(observer.error);
            assertTrue(observer.completed);
            assertTrue(observer.value.getSuccess());
            assertEquals(2, observer.value.getResultsCount());
            assertTrue(observer.value.getResultsList().stream().allMatch(result -> result.getSuccess()));
            assertEquals(0, observer.value.getResults(0).getRequestIndex());
            assertEquals("doc-1", observer.value.getResults(0).getId());
            assertEquals(1, observer.value.getResults(1).getRequestIndex());
            assertEquals("doc-2", observer.value.getResults(1).getId());

            RecordingObserver<BulkDeleteDocumentResponse> emptyObserver = new RecordingObserver<>();
            service.bulkDeleteDocument(
                    BulkDeleteDocumentRequest.newBuilder().setPartitionId("0").build(), emptyObserver);
            assertTrue(emptyObserver.completed);
            assertTrue(emptyObserver.value.getSuccess());
            assertEquals(0, emptyObserver.value.getResultsCount());
        }
    }

    @Test
    void bulkDeleteMakesDocumentsImmediatelyAbsentFromSearchAndIsIdempotentOnRetry() throws IOException {
        try (IndexManager manager = manager("bulk-delete-visibility")) {
            IndexServiceImpl service = new IndexServiceImpl(manager);
            service.indexDocument(
                    IndexDocumentRequest.newBuilder()
                            .setPartitionId("0")
                            .setDocument(document("doc-1", "durable content"))
                            .build(),
                    new RecordingObserver<>());
            service.indexDocument(
                    IndexDocumentRequest.newBuilder()
                            .setPartitionId("0")
                            .setDocument(document("doc-2", "durable content"))
                            .build(),
                    new RecordingObserver<>());

            RecordingObserver<BulkDeleteDocumentResponse> firstDelete = new RecordingObserver<>();
            service.bulkDeleteDocument(
                    BulkDeleteDocumentRequest.newBuilder()
                            .setPartitionId("0")
                            .addIds("doc-1")
                            .addIds("doc-2")
                            .addIds("doc-1") // duplicate: preserved as its own ordered outcome
                            .addIds("never-existed")
                            .build(),
                    firstDelete);

            assertTrue(firstDelete.completed);
            assertTrue(firstDelete.value.getSuccess());
            assertEquals(4, firstDelete.value.getResultsCount());
            assertTrue(firstDelete.value.getResultsList().stream().allMatch(result -> result.getSuccess()));

            assertEquals(
                    0,
                    manager.searchDocument("0", "durable", 10, 0, SearchType.BM25)
                            .getTotalHits());

            RecordingObserver<BulkDeleteDocumentResponse> retryDelete = new RecordingObserver<>();
            service.bulkDeleteDocument(
                    BulkDeleteDocumentRequest.newBuilder()
                            .setPartitionId("0")
                            .addIds("doc-1")
                            .addIds("doc-2")
                            .build(),
                    retryDelete);
            assertTrue(retryDelete.completed);
            assertTrue(retryDelete.value.getSuccess());
            assertTrue(retryDelete.value.getResultsList().stream().allMatch(result -> result.getSuccess()));
        }
    }

    @Test
    void bulkDeleteAdmissionExhaustionReturnsPartialResultsForPriorCommits() throws IOException {
        try (IndexManager manager = new AdmissionExhaustingDeleteManager(tempDir.resolve("bulk-delete-admission"), 2)) {
            RecordingObserver<BulkDeleteDocumentResponse> observer = new RecordingObserver<>();

            new IndexServiceImpl(manager)
                    .bulkDeleteDocument(
                            BulkDeleteDocumentRequest.newBuilder()
                                    .setPartitionId("0")
                                    .addIds("doc-1")
                                    .addIds("doc-2")
                                    .addIds("doc-3")
                                    .build(),
                            observer);

            assertNull(observer.error);
            assertTrue(observer.completed);
            assertFalse(observer.value.getSuccess());
            assertEquals(3, observer.value.getResultsCount());

            var firstResult = observer.value.getResults(0);
            assertTrue(firstResult.getSuccess());
            assertEquals("doc-1", firstResult.getId());

            var admissionResult = observer.value.getResults(1);
            assertFalse(admissionResult.getSuccess());
            assertEquals("doc-2", admissionResult.getId());
            assertTrue(admissionResult.getError().contains("retry with the same id"));

            var thirdResult = observer.value.getResults(2);
            assertTrue(thirdResult.getSuccess());
            assertEquals("doc-3", thirdResult.getId());
        }
    }

    @Test
    void bulkDeleteDurableFailureMarksOnlyThatItemWhileCommittingOthers() throws IOException {
        try (IndexManager manager = new FailingDeleteManager(tempDir.resolve("bulk-delete-failure"), 2)) {
            RecordingObserver<BulkDeleteDocumentResponse> observer = new RecordingObserver<>();

            new IndexServiceImpl(manager)
                    .bulkDeleteDocument(
                            BulkDeleteDocumentRequest.newBuilder()
                                    .setPartitionId("0")
                                    .addIds("doc-1")
                                    .addIds("doc-2")
                                    .addIds("doc-3")
                                    .build(),
                            observer);

            assertTrue(observer.completed);
            assertFalse(observer.value.getSuccess());
            assertTrue(observer.value.getResults(0).getSuccess());
            assertFalse(observer.value.getResults(1).getSuccess());
            assertTrue(observer.value.getResults(1).getError().contains("retry with the same id"));
            assertTrue(observer.value.getResults(2).getSuccess());
        }
    }

    @Test
    void bulkDeleteRejectsOverBudgetItemCountBeforeAnyDelete() throws IOException {
        try (IndexManager manager = manager("bulk-delete-budget")) {
            AppConfig.RequestLimitsConfig limits = new AppConfig.RequestLimitsConfig();
            limits.setMaxBulkItems(1);
            IndexServiceImpl service = new IndexServiceImpl(manager, limits);
            RecordingObserver<BulkDeleteDocumentResponse> observer = new RecordingObserver<>();

            service.bulkDeleteDocument(
                    BulkDeleteDocumentRequest.newBuilder()
                            .setPartitionId("0")
                            .addIds("doc-1")
                            .addIds("doc-2")
                            .build(),
                    observer);

            assertInvalidArgument(observer.error);
        }
    }

    @Test
    void deleteDocumentReturnsSuccessAndSearchMapsHitsAndFacets() throws IOException {
        try (IndexManager manager = manager("read")) {
            IndexServiceImpl service = new IndexServiceImpl(manager);
            RecordingObserver<IndexDocumentResponse> indexObserver = new RecordingObserver<>();
            service.indexDocument(
                    IndexDocumentRequest.newBuilder()
                            .setPartitionId("0")
                            .setDocument(document("doc-1", "searchable content"))
                            .build(),
                    indexObserver);

            RecordingObserver<IndexSearchResponse> searchObserver = new RecordingObserver<>();
            service.searchIndex(
                    IndexSearchRequest.newBuilder()
                            .setPartitionId("0")
                            .setQuery("searchable")
                            .setSize(10)
                            .setSearchType(SearchType.BM25)
                            .setHighlight(true)
                            .addFacets(FacetRequest.newBuilder()
                                    .setField("category")
                                    .setSize(5)
                                    .build())
                            .build(),
                    searchObserver);

            assertNull(searchObserver.error);
            assertTrue(searchObserver.completed);
            assertEquals(1, searchObserver.value.getTotalHits());
            assertEquals("doc-1", searchObserver.value.getHits(0).getDocId());

            RecordingObserver<DeleteDocumentResponse> deleteObserver = new RecordingObserver<>();
            service.deleteDocument(
                    DeleteDocumentRequest.newBuilder()
                            .setPartitionId("0")
                            .setId("doc-1")
                            .build(),
                    deleteObserver);
            assertNull(deleteObserver.error);
            assertTrue(deleteObserver.completed);
            assertTrue(deleteObserver.value.getSuccess());

            RecordingObserver<IndexSearchResponse> postDeleteSearchObserver = new RecordingObserver<>();
            service.searchIndex(
                    IndexSearchRequest.newBuilder()
                            .setPartitionId("0")
                            .setQuery("searchable")
                            .setSize(10)
                            .setSearchType(SearchType.BM25)
                            .build(),
                    postDeleteSearchObserver);
            assertNull(postDeleteSearchObserver.error);
            assertTrue(postDeleteSearchObserver.completed);
            assertEquals(0, postDeleteSearchObserver.value.getTotalHits());
        }
    }

    @Test
    void missingShardSearchCompletesWithEmptyResult() throws IOException {
        try (IndexManager manager = manager("missing")) {
            RecordingObserver<IndexSearchResponse> observer = new RecordingObserver<>();

            new IndexServiceImpl(manager)
                    .searchIndex(
                            IndexSearchRequest.newBuilder()
                                    .setPartitionId("missing")
                                    .setQuery("anything")
                                    .setSize(10)
                                    .setSearchType(SearchType.BM25)
                                    .build(),
                            observer);

            assertNull(observer.error);
            assertTrue(observer.completed);
            assertEquals(0, observer.value.getTotalHits());
            assertEquals(0, observer.value.getHitsCount());
        }
    }

    @Test
    void storedFieldProjectionIsPresenceAwareAndReducesLargeHitPayloads() throws IOException {
        try (IndexManager manager = manager("projection")) {
            IndexServiceImpl service = new IndexServiceImpl(manager);
            String largeContent = "needle " + "payload ".repeat(6000);
            RecordingObserver<IndexDocumentResponse> indexed = new RecordingObserver<>();
            service.indexDocument(
                    IndexDocumentRequest.newBuilder()
                            .setPartitionId("0")
                            .setDocument(Document.newBuilder()
                                    .setId("doc-large")
                                    .addFields(Field.newBuilder()
                                            .setName(ShardIndex.FIELD_TITLE)
                                            .setValue("Needle title"))
                                    .addFields(Field.newBuilder()
                                            .setName(ShardIndex.FIELD_CONTENT)
                                            .setValue(largeContent)))
                            .build(),
                    indexed);
            assertTrue(indexed.completed);

            IndexSearchResponse full = search(
                    service,
                    IndexSearchRequest.newBuilder()
                            .setPartitionId("0")
                            .setQuery("needle")
                            .setSize(10)
                            .setSearchType(SearchType.BM25)
                            .setHighlight(true)
                            .build());
            IndexSearchResponse selected = search(
                    service,
                    IndexSearchRequest.newBuilder()
                            .setPartitionId("0")
                            .setQuery("needle")
                            .setSize(10)
                            .setSearchType(SearchType.BM25)
                            .setHighlight(true)
                            .setStoredFieldSelection(StoredFieldSelection.newBuilder()
                                    .addFields(ShardIndex.FIELD_TITLE)
                                    .addFields("missing"))
                            .build());
            IndexSearchResponse empty = search(
                    service,
                    IndexSearchRequest.newBuilder()
                            .setPartitionId("0")
                            .setQuery("needle")
                            .setSize(10)
                            .setSearchType(SearchType.BM25)
                            .setHighlight(true)
                            .setStoredFieldSelection(StoredFieldSelection.getDefaultInstance())
                            .build());

            assertTrue(full.getHits(0).hasTitle());
            assertTrue(full.getHits(0).hasContent());
            assertTrue(selected.getHits(0).hasTitle());
            assertFalse(selected.getHits(0).hasContent());
            assertTrue(selected.getHits(0).getFieldsMap().isEmpty());
            assertTrue(
                    selected.getHits(0).getHighlightedFieldsMap().keySet().stream()
                            .allMatch(ShardIndex.FIELD_TITLE::equals),
                    "excluded content must not leak through highlights");
            assertFalse(empty.getHits(0).hasTitle());
            assertFalse(empty.getHits(0).hasContent());
            assertTrue(empty.getHits(0).getFieldsMap().isEmpty());
            assertTrue(empty.getHits(0).getHighlightedFieldsMap().isEmpty());
            assertEquals(full.getTotalHits(), selected.getTotalHits());
            assertEquals(full.getHits(0).getDocId(), empty.getHits(0).getDocId());
            assertEquals(full.getHits(0).getScore(), empty.getHits(0).getScore());
            assertTrue(
                    selected.getSerializedSize() * 20 < full.getSerializedSize(),
                    "fixed large-content fixture should reduce response bytes by more than 95%; full="
                            + full.getSerializedSize() + ", selected=" + selected.getSerializedSize());
        }
    }

    private static IndexSearchResponse search(IndexServiceImpl service, IndexSearchRequest request) {
        RecordingObserver<IndexSearchResponse> observer = new RecordingObserver<>();
        service.searchIndex(request, observer);
        assertNull(observer.error);
        assertTrue(observer.completed);
        return observer.value;
    }

    @Test
    void invalidPartitionIsMappedToInvalidArgumentForEveryRpc() throws IOException {
        try (IndexManager manager = manager("invalid")) {
            IndexServiceImpl service = new IndexServiceImpl(manager);
            RecordingObserver<IndexDocumentResponse> index = new RecordingObserver<>();
            service.indexDocument(
                    IndexDocumentRequest.newBuilder()
                            .setPartitionId("../escape")
                            .setDocument(document("doc", "content"))
                            .build(),
                    index);
            assertInvalidArgument(index.error);

            RecordingObserver<BulkIndexDocumentResponse> bulk = new RecordingObserver<>();
            service.bulkIndexDocument(
                    BulkIndexDocumentRequest.newBuilder()
                            .setPartitionId("bad/path")
                            .build(),
                    bulk);
            assertInvalidArgument(bulk.error);

            RecordingObserver<BulkDeleteDocumentResponse> bulkDelete = new RecordingObserver<>();
            service.bulkDeleteDocument(
                    BulkDeleteDocumentRequest.newBuilder()
                            .setPartitionId("bad/path")
                            .build(),
                    bulkDelete);
            assertInvalidArgument(bulkDelete.error);

            RecordingObserver<DeleteDocumentResponse> delete = new RecordingObserver<>();
            service.deleteDocument(
                    DeleteDocumentRequest.newBuilder()
                            .setPartitionId("bad.path")
                            .setId("doc")
                            .build(),
                    delete);
            assertInvalidArgument(delete.error);

            RecordingObserver<IndexSearchResponse> search = new RecordingObserver<>();
            service.searchIndex(
                    IndexSearchRequest.newBuilder()
                            .setPartitionId("")
                            .setQuery("query")
                            .setSize(1)
                            .build(),
                    search);
            assertInvalidArgument(search.error);
        }
    }

    @Test
    void deleteAndSearchIoFailuresAreReturnedToObservers() throws IOException {
        try (IndexManager manager = new FailingManager(tempDir.resolve("failures"))) {
            IndexServiceImpl service = new IndexServiceImpl(manager);
            RecordingObserver<DeleteDocumentResponse> delete = new RecordingObserver<>();
            service.deleteDocument(
                    DeleteDocumentRequest.newBuilder()
                            .setPartitionId("0")
                            .setId("doc")
                            .build(),
                    delete);
            assertEquals(Status.Code.INTERNAL, status(delete.error).getStatus().getCode());
            assertFalse(delete.completed);

            RecordingObserver<IndexSearchResponse> search = new RecordingObserver<>();
            service.searchIndex(
                    IndexSearchRequest.newBuilder()
                            .setPartitionId("0")
                            .setQuery("query")
                            .setSize(1)
                            .build(),
                    search);
            assertEquals(Status.Code.INTERNAL, status(search.error).getStatus().getCode());
            assertFalse(search.completed);
        }
    }

    private IndexManager manager(String name) {
        return new IndexManager(tempDir.resolve(name), 10, Duration.ofHours(1), null, FAKE_EMBEDDER);
    }

    private static Document document(String id, String content) {
        return Document.newBuilder()
                .setId(id)
                .addFields(Field.newBuilder().setName(ShardIndex.FIELD_CONTENT).setValue(content))
                .build();
    }

    private static void assertInvalidArgument(Throwable error) {
        assertEquals(Status.Code.INVALID_ARGUMENT, status(error).getStatus().getCode());
        assertFalse(error instanceof StatusRuntimeException runtimeException
                && runtimeException.getStatus().getDescription() == null);
    }

    private static StatusRuntimeException status(Throwable error) {
        return assertInstanceOf(StatusRuntimeException.class, error);
    }

    private static final class FailingManager extends IndexManager {
        FailingManager(Path baseDir) {
            super(baseDir, 10, Duration.ofHours(1), null, FAKE_EMBEDDER);
        }

        @Override
        public void deleteDocumentDurably(String partitionId, String docId) throws IOException {
            throw new IOException("delete failure");
        }

        @Override
        public SearchResult searchDocument(
                String partitionId,
                String query,
                int limit,
                int from,
                SearchType searchType,
                java.util.List<Filter> filters,
                boolean highlight,
                java.util.List<FacetRequest> facetRequests,
                com.danieljhkim.dsearch.common.pagination.SortOptions sortOptions)
                throws IOException {
            throw new IOException("search failure");
        }
    }

    private static final class AdmissionExhaustingManager extends IndexManager {
        private final int failingCall;
        private int calls;

        AdmissionExhaustingManager(Path baseDir, int failingCall) {
            super(baseDir, 10, Duration.ofHours(1), null, FAKE_EMBEDDER);
            this.failingCall = failingCall;
        }

        @Override
        public void indexDocumentDurably(String partitionId, SearchDocument document) throws IOException {
            if (++calls == failingCall) {
                throw new RequestAdmissionException("embedding predictor", 100);
            }
            super.indexDocumentDurably(partitionId, document);
        }
    }

    private static final class AdmissionExhaustingDeleteManager extends IndexManager {
        private final int failingCall;
        private int calls;

        AdmissionExhaustingDeleteManager(Path baseDir, int failingCall) {
            super(baseDir, 10, Duration.ofHours(1), null, FAKE_EMBEDDER);
            this.failingCall = failingCall;
        }

        @Override
        public void deleteDocumentDurably(String partitionId, String docId) throws IOException {
            if (++calls == failingCall) {
                throw new RequestAdmissionException("delete admission", 100);
            }
            super.deleteDocumentDurably(partitionId, docId);
        }
    }

    private static final class FailingDeleteManager extends IndexManager {
        private final int failingCall;
        private int calls;

        FailingDeleteManager(Path baseDir, int failingCall) {
            super(baseDir, 10, Duration.ofHours(1), null, FAKE_EMBEDDER);
            this.failingCall = failingCall;
        }

        @Override
        public void deleteDocumentDurably(String partitionId, String docId) throws IOException {
            if (++calls == failingCall) {
                throw new IOException("delete failure for " + docId);
            }
            super.deleteDocumentDurably(partitionId, docId);
        }
    }

    private static final class RecordingObserver<T> implements StreamObserver<T> {
        private T value;
        private Throwable error;
        private boolean completed;

        @Override
        public void onNext(T value) {
            this.value = value;
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
        }

        @Override
        public void onCompleted() {
            completed = true;
        }
    }
}
