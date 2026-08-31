package com.danieljhkim.dsearch.common.pagination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.danieljhkim.dsearch.common.exception.InvalidCursorException;
import com.danieljhkim.dsearch.common.schema.AnalyzerConfig;
import com.danieljhkim.dsearch.common.schema.EmbeddingModelIdentity;
import com.danieljhkim.dsearch.common.schema.IndexSchema;
import com.danieljhkim.dsearch.proto.common.Filter;
import com.danieljhkim.dsearch.proto.common.FilterOperator;
import com.danieljhkim.dsearch.proto.common.SearchCursorPayload;
import com.danieljhkim.dsearch.proto.common.SortOrder;
import com.danieljhkim.dsearch.proto.common.SortValue;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchCursorCodecTest {

    private static final IndexSchema SCHEMA = IndexSchema.current(
            AnalyzerConfig.standard(), List.of(), EmbeddingModelIdentity.of("model-a", "PyTorch", 8));

    private final SearchCursorCodec codec = new SearchCursorCodec("test-signing-key");

    private static final List<SortValue> SORT_VALUES = List.of(SortValues.of(42L), SortValues.of("doc-7"));

    @Test
    void roundTripsThroughAnOpaqueString() {
        byte[] fingerprint = fingerprint("coffee", List.of());
        String cursor = codec.encode(fingerprint, 3L, SORT_VALUES, 128L);

        SearchCursorPayload payload = codec.decode(cursor, fingerprint, 3L);

        assertEquals(SearchCursorCodec.CURRENT_VERSION, payload.getVersion());
        assertEquals(3L, payload.getIndexGeneration());
        assertEquals(128L, payload.getTotalHits());
        assertEquals(SORT_VALUES, payload.getSortValuesList());
    }

    @Test
    void detectsAnAlteredPayload() {
        byte[] fingerprint = fingerprint("coffee", List.of());
        String cursor = codec.encode(fingerprint, 1L, SORT_VALUES, 10L);

        // Re-sign nothing: swap in a payload the attacker built, keeping the original signature.
        String forgedPayload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(SearchCursorPayload.newBuilder()
                        .setVersion(SearchCursorCodec.CURRENT_VERSION)
                        .setIndexGeneration(1L)
                        .addAllSortValues(List.of(SortValues.of(0L), SortValues.of("doc-0")))
                        .build()
                        .toByteArray());
        String tampered = "v1." + forgedPayload + "." + cursor.split("\\.")[2];

        assertEquals(
                InvalidCursorException.Reason.TAMPERED,
                assertThrows(InvalidCursorException.class, () -> codec.decode(tampered, fingerprint, 1L))
                        .getReason());
    }

    @Test
    void rejectsACursorSignedByAnotherQueryNodesKey() {
        byte[] fingerprint = fingerprint("coffee", List.of());
        String fromOtherNode = new SearchCursorCodec("a-different-key").encode(fingerprint, 1L, SORT_VALUES, 10L);

        assertEquals(
                InvalidCursorException.Reason.TAMPERED,
                assertThrows(InvalidCursorException.class, () -> codec.decode(fromOtherNode, fingerprint, 1L))
                        .getReason());
    }

    @Test
    void rejectsMalformedCursors() {
        byte[] fingerprint = fingerprint("coffee", List.of());
        for (String malformed : List.of("", "   ", "not-a-cursor", "v1.only-two-parts", "v1.!!!not-base64!!!.abc")) {
            assertEquals(
                    InvalidCursorException.Reason.MALFORMED,
                    assertThrows(InvalidCursorException.class, () -> codec.decode(malformed, fingerprint, 1L))
                            .getReason(),
                    "expected MALFORMED for '" + malformed + "'");
        }
    }

    @Test
    void rejectsAnUnknownCursorFormat() {
        byte[] fingerprint = fingerprint("coffee", List.of());
        String cursor = codec.encode(fingerprint, 1L, SORT_VALUES, 10L);
        String futureFormat = "v2." + cursor.split("\\.")[1] + "." + cursor.split("\\.")[2];

        assertEquals(
                InvalidCursorException.Reason.UNSUPPORTED_VERSION,
                assertThrows(InvalidCursorException.class, () -> codec.decode(futureFormat, fingerprint, 1L))
                        .getReason());
    }

    @Test
    void rejectsACursorWhoseQueryChanged() {
        String cursor = codec.encode(fingerprint("coffee", List.of()), 1L, SORT_VALUES, 10L);

        assertEquals(
                InvalidCursorException.Reason.REQUEST_CHANGED,
                assertThrows(
                                InvalidCursorException.class,
                                () -> codec.decode(cursor, fingerprint("tea", List.of()), 1L))
                        .getReason());
    }

    @Test
    void rejectsACursorWhoseFiltersChanged() {
        Filter tenantAcme = filter("tenant", "acme");
        Filter tenantOther = filter("tenant", "other");
        String cursor = codec.encode(fingerprint("coffee", List.of(tenantAcme)), 1L, SORT_VALUES, 10L);

        assertEquals(
                InvalidCursorException.Reason.REQUEST_CHANGED,
                assertThrows(
                                InvalidCursorException.class,
                                () -> codec.decode(cursor, fingerprint("coffee", List.of(tenantOther)), 1L))
                        .getReason());
    }

    @Test
    void rejectsACursorWhoseSortChanged() {
        byte[] byPrice = RequestFingerprint.of(
                "coffee", "shard-a", "BM25", "RRF", List.of(), sortSpec("price", SortOrder.SORT_ORDER_ASC), 10, SCHEMA);
        byte[] byPriceDescending = RequestFingerprint.of(
                "coffee",
                "shard-a",
                "BM25",
                "RRF",
                List.of(),
                sortSpec("price", SortOrder.SORT_ORDER_DESC),
                10,
                SCHEMA);
        String cursor = codec.encode(byPrice, 1L, SORT_VALUES, 10L);

        assertEquals(
                InvalidCursorException.Reason.REQUEST_CHANGED,
                assertThrows(InvalidCursorException.class, () -> codec.decode(cursor, byPriceDescending, 1L))
                        .getReason());
    }

    @Test
    void rejectsACursorWhosePageSizeChanged() {
        byte[] tenPerPage = RequestFingerprint.of(
                "coffee", "shard-a", "BM25", "RRF", List.of(), sortSpec("price", SortOrder.SORT_ORDER_ASC), 10, SCHEMA);
        byte[] twentyPerPage = RequestFingerprint.of(
                "coffee", "shard-a", "BM25", "RRF", List.of(), sortSpec("price", SortOrder.SORT_ORDER_ASC), 20, SCHEMA);
        String cursor = codec.encode(tenPerPage, 1L, SORT_VALUES, 10L);

        assertEquals(
                InvalidCursorException.Reason.REQUEST_CHANGED,
                assertThrows(InvalidCursorException.class, () -> codec.decode(cursor, twentyPerPage, 1L))
                        .getReason());
    }

    @Test
    void rejectsACursorIssuedAgainstAnOlderIndexGeneration() {
        byte[] fingerprint = fingerprint("coffee", List.of());
        String cursor = codec.encode(fingerprint, 1L, SORT_VALUES, 10L);

        InvalidCursorException error =
                assertThrows(InvalidCursorException.class, () -> codec.decode(cursor, fingerprint, 2L));
        assertEquals(InvalidCursorException.Reason.INDEX_CHANGED, error.getReason());
    }

    @Test
    void filterOrderDoesNotChangeTheFingerprint() {
        Filter tenant = filter("tenant", "acme");
        Filter status = filter("status", "live");

        // A conjunction of filters describes the same result set regardless of arrival order, so a
        // client that rebuilds its filter list between pages must still be able to resume.
        assertEquals(
                new String(fingerprint("coffee", List.of(tenant, status)), StandardCharsets.ISO_8859_1),
                new String(fingerprint("coffee", List.of(status, tenant)), StandardCharsets.ISO_8859_1));
    }

    @Test
    void aSchemaChangeChangesTheFingerprint() {
        byte[] withSchema = RequestFingerprint.of(
                "coffee", "shard-a", "BM25", "RRF", List.of(), sortSpec("price", SortOrder.SORT_ORDER_ASC), 10, SCHEMA);
        byte[] withOtherAnalyzer = RequestFingerprint.of(
                "coffee",
                "shard-a",
                "BM25",
                "RRF",
                List.of(),
                sortSpec("price", SortOrder.SORT_ORDER_ASC),
                10,
                IndexSchema.current(
                        AnalyzerConfig.of("keyword"), List.of(), EmbeddingModelIdentity.of("model-a", "PyTorch", 8)));

        assertNotEquals(
                new String(withSchema, StandardCharsets.ISO_8859_1),
                new String(withOtherAnalyzer, StandardCharsets.ISO_8859_1));
    }

    private static byte[] fingerprint(String query, List<Filter> filters) {
        return RequestFingerprint.of(
                query, "shard-a", "BM25", "RRF", filters, sortSpec("price", SortOrder.SORT_ORDER_ASC), 10, SCHEMA);
    }

    private static SortSpec sortSpec(String field, SortOrder order) {
        return SortSpec.effective(
                List.of(com.danieljhkim.dsearch.proto.common.SortField.newBuilder()
                        .setField(field)
                        .setOrder(order)
                        .build()),
                false);
    }

    private static Filter filter(String field, String value) {
        return Filter.newBuilder()
                .setField(field)
                .setOperator(FilterOperator.EQ)
                .addValues(value)
                .build();
    }
}
