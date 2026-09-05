package com.danieljhkim.dsearch.common.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.proto.common.FacetRequest;
import com.danieljhkim.dsearch.proto.common.SortField;
import com.danieljhkim.dsearch.proto.common.SortOrder;
import com.danieljhkim.dsearch.proto.index.Document;
import com.danieljhkim.dsearch.proto.index.Field;
import com.danieljhkim.dsearch.proto.query.QueryRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequestLimitsValidatorTest {

    @Test
    void testValidatePageSize_WithinLimit() {
        assertDoesNotThrow(() -> RequestLimitsValidator.validatePageSize(10, 100));
        assertDoesNotThrow(() -> RequestLimitsValidator.validatePageSize(100, 100));
        assertDoesNotThrow(() -> RequestLimitsValidator.validatePageSize(0, 100));
    }

    @Test
    void testValidatePageSize_ExceedsLimit() {
        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> RequestLimitsValidator.validatePageSize(101, 100));
        assert exception.getMessage().contains("Requested pageSize (101) exceeds maximum allowed (100)");
    }

    @Test
    void testValidatePageSize_ExceedsLimit_LargeValue() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> RequestLimitsValidator.validatePageSize(10000, 1000));
        assert exception.getMessage().contains("Requested pageSize (10000) exceeds maximum allowed (1000)");
    }

    @Test
    void testValidateQueryLength_WithinLimit() {
        assertDoesNotThrow(() -> RequestLimitsValidator.validateQueryLength("test query", 100));
        assertDoesNotThrow(() -> RequestLimitsValidator.validateQueryLength("a".repeat(100), 100));
        assertDoesNotThrow(() -> RequestLimitsValidator.validateQueryLength("", 100));
    }

    @Test
    void testValidateQueryLength_NullQuery() {
        assertDoesNotThrow(() -> RequestLimitsValidator.validateQueryLength(null, 100));
    }

    @Test
    void testValidateQueryLength_ExceedsLimit() {
        String longQuery = "a".repeat(101);
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> RequestLimitsValidator.validateQueryLength(longQuery, 100));
        assert exception.getMessage().contains("Query length (101) exceeds maximum allowed (100)");
    }

    @Test
    void testValidateQueryLength_ExceedsLimit_LargeValue() {
        String longQuery = "a".repeat(2048);
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> RequestLimitsValidator.validateQueryLength(longQuery, 1024));
        assert exception.getMessage().contains("Query length (2048) exceeds maximum allowed (1024)");
    }

    @Test
    void overflowSizedPageIsRejectedBeforeFanout() {
        AppConfig.RequestLimitsConfig limits = new AppConfig.RequestLimitsConfig();
        limits.setMaxSize(1000);
        limits.setMaxResultWindow(10000);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> RequestLimitsValidator.validateSearchWindow("query", Integer.MAX_VALUE, 1000, limits));

        assert exception.getMessage().contains("result window");
        assert exception.getMessage().contains("cursor pagination");
    }

    @Test
    void deeplyNestedFacetsAreRejectedAtTheGrpcBoundary() {
        AppConfig.RequestLimitsConfig limits = new AppConfig.RequestLimitsConfig();
        limits.setMaxFacetDepth(2);
        FacetRequest depthThree = FacetRequest.newBuilder()
                .setField("level-1")
                .addNested(FacetRequest.newBuilder()
                        .setField("level-2")
                        .addNested(FacetRequest.newBuilder().setField("level-3")))
                .build();
        QueryRequest request = QueryRequest.newBuilder()
                .setQueryString("query")
                .setSize(10)
                .addFacets(depthThree)
                .build();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> RequestLimitsValidator.validateQueryRequest(request, limits));

        assert exception.getMessage().contains("Facet depth (3)");
    }

    @Test
    void defaultLimitsAcceptOrdinaryNestedFacetWork() {
        AppConfig.RequestLimitsConfig limits = new AppConfig.RequestLimitsConfig();
        FacetRequest ordinaryTree = nestedFacetTree(10);
        QueryRequest request = QueryRequest.newBuilder()
                .setQueryString("query")
                .setSize(10)
                .addFacets(ordinaryTree)
                .build();

        assertDoesNotThrow(() -> RequestLimitsValidator.validateQueryRequest(request, limits));
    }

    @Test
    void defaultLimitsRejectAmplifiedNestedFacetWorkBeforeFanout() {
        AppConfig.RequestLimitsConfig limits = new AppConfig.RequestLimitsConfig();
        QueryRequest request = QueryRequest.newBuilder()
                .setQueryString("query")
                .setSize(10)
                .addFacets(nestedFacetTree(1000))
                .build();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> RequestLimitsValidator.validateQueryRequest(request, limits));

        assertTrue(exception.getMessage().contains("Expanded facet bucket upper bound"));
        assertTrue(exception.getMessage().contains(Long.toString(limits.getMaxFacetExpandedBuckets())));
    }

    @Test
    void expandedFacetWorkHonorsTheConfiguredBoundary() {
        FacetRequest twoLevels = FacetRequest.newBuilder()
                .setField("parent")
                .setSize(2)
                .addNested(FacetRequest.newBuilder().setField("child").setSize(3))
                .build();
        AppConfig.RequestLimitsConfig accepted = new AppConfig.RequestLimitsConfig();
        accepted.setMaxFacetExpandedBuckets(8);
        AppConfig.RequestLimitsConfig rejected = new AppConfig.RequestLimitsConfig();
        rejected.setMaxFacetExpandedBuckets(7);

        assertDoesNotThrow(() -> RequestLimitsValidator.validateSearchStructures(null, List.of(twoLevels), accepted));
        assertThrows(
                IllegalArgumentException.class,
                () -> RequestLimitsValidator.validateSearchStructures(null, List.of(twoLevels), rejected));
    }

    @Test
    void facetLocalFiltersAreRejectedAtTheGrpcBoundary() {
        QueryRequest request = QueryRequest.newBuilder()
                .setQueryString("query")
                .setSize(10)
                .addFacets(FacetRequest.newBuilder()
                        .setField("category")
                        .addFilters(com.danieljhkim.dsearch.proto.common.Filter.getDefaultInstance()))
                .build();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> RequestLimitsValidator.validateQueryRequest(request, new AppConfig.RequestLimitsConfig()));

        assertTrue(exception.getMessage().contains("Facet-level filters are not supported"));
    }

    @Test
    void oversizedDocumentFieldsAndAggregatePayloadAreRejected() {
        AppConfig.RequestLimitsConfig fieldLimits = new AppConfig.RequestLimitsConfig();
        fieldLimits.setMaxFieldsPerDocument(1);
        Document tooManyFields = Document.newBuilder()
                .setId("doc")
                .addFields(Field.newBuilder().setName("one").setValue("1"))
                .addFields(Field.newBuilder().setName("two").setValue("2"))
                .build();
        assertThrows(
                IllegalArgumentException.class,
                () -> RequestLimitsValidator.validateDocument(tooManyFields, fieldLimits));

        AppConfig.RequestLimitsConfig payloadLimits = new AppConfig.RequestLimitsConfig();
        payloadLimits.setMaxFieldValueBytes(1024);
        payloadLimits.setMaxIndexPayloadBytes(8);
        Document oversized = Document.newBuilder()
                .setId("doc")
                .addFields(Field.newBuilder().setName("body").setValue("payload"))
                .build();
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> RequestLimitsValidator.validateDocument(oversized, payloadLimits));
        assert exception.getMessage().contains("Index payload bytes");
    }

    @Test
    void bulkItemAndEmbeddingWorkLimitsAreRejectedBeforeDispatch() {
        AppConfig.RequestLimitsConfig limits = new AppConfig.RequestLimitsConfig();
        limits.setMaxBulkItems(1);
        limits.setMaxBulkEmbeddingBytes(4);

        IllegalArgumentException itemCount = assertThrows(
                IllegalArgumentException.class, () -> RequestLimitsValidator.validateBulkItemCount(2, limits));
        IllegalArgumentException embeddingBytes = assertThrows(
                IllegalArgumentException.class, () -> RequestLimitsValidator.validateBulkEmbeddingBytes(5, limits));

        assertTrue(itemCount.getMessage().contains("Bulk item count"));
        assertTrue(embeddingBytes.getMessage().contains("Bulk embedding bytes"));
    }

    private static FacetRequest nestedFacetTree(int size) {
        return FacetRequest.newBuilder()
                .setField("level-1")
                .setSize(size)
                .addNested(FacetRequest.newBuilder()
                        .setField("level-2")
                        .setSize(size)
                        .addNested(FacetRequest.newBuilder().setField("level-3").setSize(size)))
                .build();
    }

    // ---------- Offset window and cursor pagination ----------

    @Test
    void offsetPagingIsBoundedByTheConfiguredResultWindow() {
        AppConfig.RequestLimitsConfig limits = new AppConfig.RequestLimitsConfig();
        limits.setMaxResultWindow(100);

        assertDoesNotThrow(() -> RequestLimitsValidator.validateSearchWindow("q", 9, 10, limits));

        IllegalArgumentException exceeded = assertThrows(
                IllegalArgumentException.class, () -> RequestLimitsValidator.validateSearchWindow("q", 10, 10, limits));
        assertTrue(exceeded.getMessage().contains("exceeds maximum allowed (100)"));
        assertTrue(
                exceeded.getMessage().contains("use cursor pagination"),
                "the limit must point at the bounded alternative");
    }

    @Test
    void aDeepOffsetPageIsRejectedWithoutOverflowing() {
        AppConfig.RequestLimitsConfig limits = new AppConfig.RequestLimitsConfig();

        // page * size overflows a signed int; the window check must reject rather than wrap.
        assertThrows(
                IllegalArgumentException.class,
                () -> RequestLimitsValidator.validateSearchWindow("q", Integer.MAX_VALUE, 1000, limits));
    }

    @Test
    void aCursorPageIsNotBoundedByTheOffsetWindow() {
        AppConfig.RequestLimitsConfig limits = new AppConfig.RequestLimitsConfig();
        limits.setMaxResultWindow(100);

        // The cursor's cost is one page per node however deep the traversal has gone, so the offset
        // window that protects deep offset paging does not apply to it.
        assertDoesNotThrow(() -> RequestLimitsValidator.validateQueryRequest(
                QueryRequest.newBuilder()
                        .setQueryString("q")
                        .setSize(10)
                        .setCursor("v1.payload.signature")
                        .build(),
                limits));
    }

    @Test
    void aCursorCannotBeCombinedWithOffsetPaging() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> RequestLimitsValidator.validateQueryRequest(
                        QueryRequest.newBuilder()
                                .setQueryString("q")
                                .setPage(3)
                                .setSize(10)
                                .setCursor("v1.payload.signature")
                                .build(),
                        new AppConfig.RequestLimitsConfig()));
        assertTrue(error.getMessage().contains("mutually exclusive"));
    }

    @Test
    void aCursorPageStillRespectsTheMaximumPageSize() {
        AppConfig.RequestLimitsConfig limits = new AppConfig.RequestLimitsConfig();
        limits.setMaxSize(50);

        assertThrows(
                IllegalArgumentException.class,
                () -> RequestLimitsValidator.validateQueryRequest(
                        QueryRequest.newBuilder()
                                .setQueryString("q")
                                .setSize(51)
                                .setCursor("v1.payload.signature")
                                .build(),
                        limits));
    }

    @Test
    void sortFieldCountIsBounded() {
        AppConfig.PaginationConfig pagination = new AppConfig.PaginationConfig();
        pagination.setMaxSortFields(2);

        QueryRequest.Builder request =
                QueryRequest.newBuilder().setQueryString("q").setSize(10);
        for (int i = 0; i < 3; i++) {
            request.addSort(SortField.newBuilder().setField("field-" + i).setOrder(SortOrder.SORT_ORDER_ASC));
        }

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> RequestLimitsValidator.validateQueryRequest(
                        request.build(), new AppConfig.RequestLimitsConfig(), pagination));
        assertTrue(error.getMessage().contains("Sort field count (3) exceeds maximum allowed (2)"));
    }

    @Test
    void aBlankSortFieldIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RequestLimitsValidator.validateSortFields(
                        List.of(SortField.newBuilder().setField("  ").build()), new AppConfig.PaginationConfig()));
    }

    @Test
    void analyzeTextWithinLimitPasses() {
        assertDoesNotThrow(
                () -> RequestLimitsValidator.validateAnalyzeText("hello world", new AppConfig.RequestLimitsConfig()));
    }

    @Test
    void emptyAnalyzeTextIsRejected() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> RequestLimitsValidator.validateAnalyzeText("", new AppConfig.RequestLimitsConfig()));
        assertTrue(error.getMessage().contains("must not be empty"));

        assertThrows(
                IllegalArgumentException.class,
                () -> RequestLimitsValidator.validateAnalyzeText(null, new AppConfig.RequestLimitsConfig()));
    }

    @Test
    void analyzeTextExceedingConfiguredBytesIsRejected() {
        AppConfig.RequestLimitsConfig limits = new AppConfig.RequestLimitsConfig();
        limits.setMaxAnalyzeTextBytes(10);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> RequestLimitsValidator.validateAnalyzeText("this text is far too long", limits));
        assertTrue(error.getMessage().contains("analyze text bytes"));
    }
}
