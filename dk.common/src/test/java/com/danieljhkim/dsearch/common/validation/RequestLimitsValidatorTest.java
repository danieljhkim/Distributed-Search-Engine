package com.danieljhkim.dsearch.common.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.proto.common.FacetRequest;
import com.danieljhkim.dsearch.proto.index.Document;
import com.danieljhkim.dsearch.proto.index.Field;
import com.danieljhkim.dsearch.proto.query.QueryRequest;
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
}
