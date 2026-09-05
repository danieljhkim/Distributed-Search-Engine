package com.danieljhkim.dsearch.indexnode.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.common.config.AppConfig.FieldConfig;
import com.danieljhkim.dsearch.common.enums.FieldType;
import com.danieljhkim.dsearch.common.model.SearchDocument;
import com.danieljhkim.dsearch.common.model.SearchHit;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.common.pagination.SortOptions;
import com.danieljhkim.dsearch.common.pagination.SortSpec;
import com.danieljhkim.dsearch.common.pagination.SortValues;
import com.danieljhkim.dsearch.ml.embedding.TextEmbedder;
import com.danieljhkim.dsearch.proto.common.SortOrder;
import com.danieljhkim.dsearch.proto.common.SortValue;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Shard-level ordering and {@code search_after} behaviour.
 *
 * <p>These run against a real Lucene index on purpose. The distributed merge assumes very specific
 * things about how Lucene orders missing values and how a resume marker behaves, and those
 * assumptions are only worth anything if a real index confirms them.
 */
class ShardIndexSortPaginationTest {

    private static final String SHARD_ID = "0";
    private static final TextEmbedder FAKE_EMBEDDER = ignored -> new float[] {1.0f, 0.0f, 0.0f};
    private static final String QUERY = "widget";

    @TempDir
    Path tempDir;

    @Test
    void ordersByASingleFieldInBothDirections() throws IOException {
        try (ShardIndex index = index()) {
            write(index, doc("a", 30.0, 2001, "alpha"), doc("b", 10.0, 2003, "beta"), doc("c", 20.0, 2002, "gamma"));

            assertEquals(List.of("b", "c", "a"), ids(search(index, 10, sort("price", SortOrder.SORT_ORDER_ASC))));
            assertEquals(List.of("a", "c", "b"), ids(search(index, 10, sort("price", SortOrder.SORT_ORDER_DESC))));
        }
    }

    @Test
    void appliesMultiFieldSortComponentsInOrder() throws IOException {
        try (ShardIndex index = index()) {
            write(
                    index,
                    doc("a", 10.0, 2001, "x"),
                    doc("b", 10.0, 2003, "x"),
                    doc("c", 20.0, 2002, "x"),
                    doc("d", 20.0, 2004, "x"));

            // price ascending is the primary key; year descending only separates equal prices.
            SortSpec spec = SortSpec.effective(
                    List.of(field("price", SortOrder.SORT_ORDER_ASC), field("year", SortOrder.SORT_ORDER_DESC)), false);

            assertEquals(List.of("b", "a", "d", "c"), ids(search(index, 10, spec)));
        }
    }

    @Test
    void documentsMissingASortFieldOrderLastInBothDirections() throws IOException {
        try (ShardIndex index = index()) {
            write(index, doc("has-low", 10.0, 2001, "x"), doc("has-high", 90.0, 2002, "x"), docWithoutPrice("missing"));

            // Lucene expresses "missing last" with a direction-dependent sentinel, so both
            // directions have to be checked: getting the sentinel backwards would put nulls first
            // under one of them.
            assertEquals(
                    List.of("has-low", "has-high", "missing"),
                    ids(search(index, 10, sort("price", SortOrder.SORT_ORDER_ASC))));
            assertEquals(
                    List.of("has-high", "has-low", "missing"),
                    ids(search(index, 10, sort("price", SortOrder.SORT_ORDER_DESC))));
        }
    }

    @Test
    void reportsAMissingSortValueAsMissingRatherThanAsASentinel() throws IOException {
        try (ShardIndex index = index()) {
            write(index, doc("has", 10.0, 2001, "x"), docWithoutPrice("missing"));

            List<SearchHit> hits = search(index, 10, sort("price", SortOrder.SORT_ORDER_ASC));

            assertFalse(SortValues.isMissing(hits.get(0).getSortValues().get(0)));
            assertTrue(SortValues.isMissing(hits.get(1).getSortValues().get(0)));
            // The id tie-breaker is never missing: that is what makes the ordering total.
            assertEquals("missing", hits.get(1).getSortValues().get(1).getStringValue());
        }
    }

    @Test
    void separatesEqualSortValuesWithTheDocumentIdTieBreaker() throws IOException {
        try (ShardIndex index = index()) {
            write(index, doc("c", 10.0, 2001, "x"), doc("a", 10.0, 2001, "x"), doc("b", 10.0, 2001, "x"));

            // Every document has identical sort values, so only the appended _id separates them —
            // and it must do so identically on every call, or a cursor would repeat or skip hits.
            assertEquals(List.of("a", "b", "c"), ids(search(index, 10, sort("price", SortOrder.SORT_ORDER_ASC))));
            assertEquals(List.of("a", "b", "c"), ids(search(index, 10, sort("price", SortOrder.SORT_ORDER_ASC))));
        }
    }

    @Test
    void cursorTraversalVisitsEveryDocumentExactlyOnce() throws IOException {
        try (ShardIndex index = index()) {
            List<SearchDocument> documents = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                // Deliberately duplicated prices: ties across page boundaries are exactly where an
                // unstable ordering loses or repeats documents.
                documents.add(doc("doc-" + i, i % 3 == 0 ? 5.0 : 10.0, 2000 + i, "x"));
            }
            documents.add(docWithoutPrice("doc-null"));
            write(index, documents.toArray(new SearchDocument[0]));

            SortSpec spec = sort("price", SortOrder.SORT_ORDER_ASC);
            Set<String> visited = new LinkedHashSet<>();
            int duplicates = 0;
            List<SortValue> resumeFrom = null;

            for (int page = 0; page < 20; page++) {
                SortOptions options =
                        resumeFrom == null ? SortOptions.sortedBy(spec) : new SortOptions(spec, resumeFrom);
                List<SearchHit> hits =
                        index.search(QUERY, 3, 0, null, false, null, options).getHits();
                if (hits.isEmpty()) {
                    break;
                }
                for (SearchHit hit : hits) {
                    if (!visited.add(hit.getDocId())) {
                        duplicates++;
                    }
                }
                resumeFrom = hits.get(hits.size() - 1).getSortValues();
            }

            assertEquals(0, duplicates, "cursor traversal must not repeat a document");
            assertEquals(10, visited.size(), "cursor traversal must not omit a document");
        }
    }

    @Test
    void aResumePointReturnsOnlyDocumentsStrictlyAfterIt() throws IOException {
        try (ShardIndex index = index()) {
            write(index, doc("a", 10.0, 2001, "x"), doc("b", 20.0, 2002, "x"), doc("c", 30.0, 2003, "x"));

            SortSpec spec = sort("price", SortOrder.SORT_ORDER_ASC);
            List<SearchHit> firstPage = search(index, 1, spec);
            assertEquals(List.of("a"), ids(firstPage));

            List<SearchHit> resumed = index.search(
                            QUERY,
                            10,
                            0,
                            null,
                            false,
                            null,
                            new SortOptions(spec, firstPage.get(0).getSortValues()))
                    .getHits();

            assertEquals(List.of("b", "c"), ids(resumed));
        }
    }

    @Test
    void aResumePointOnAnEmptyShardYieldsNothingRatherThanFailing() throws IOException {
        try (ShardIndex index = index()) {
            index.commit();

            SortSpec spec = sort("price", SortOrder.SORT_ORDER_ASC);
            SearchResult result = index.search(
                    QUERY,
                    10,
                    0,
                    null,
                    false,
                    null,
                    new SortOptions(spec, List.of(SortValues.of(1.0), SortValues.of("doc-1"))));

            assertTrue(result.getHits().isEmpty());
        }
    }

    @Test
    void scoreOrderingIsSelectableInBothDirections() throws IOException {
        try (ShardIndex index = index()) {
            write(index, doc("weak", 1.0, 2001, "x", "widget"), doc("strong", 2.0, 2002, "x", "widget widget widget"));

            List<String> descending = ids(search(index, 10, sort(SortSpec.SCORE_FIELD, SortOrder.SORT_ORDER_DESC)));
            List<String> ascending = ids(search(index, 10, sort(SortSpec.SCORE_FIELD, SortOrder.SORT_ORDER_ASC)));

            assertEquals(List.of("strong", "weak"), descending);
            assertEquals(List.of("weak", "strong"), ascending);
        }
    }

    @Test
    void unsortedSearchStillReportsNoSortValues() throws IOException {
        try (ShardIndex index = index()) {
            write(index, doc("a", 10.0, 2001, "x"));

            SearchHit hit =
                    index.search(QUERY, 10, 0, null, false, null).getHits().get(0);

            assertEquals(null, hit.getSortValues());
        }
    }

    @Test
    void sortingAndCursorMetadataRemainAvailableWhenTheSortFieldIsExcluded() throws IOException {
        try (ShardIndex index = index()) {
            write(index, doc("high", 90.0, 2002, "x"), doc("low", 10.0, 2001, "x"));
            SortSpec spec = sort("price", SortOrder.SORT_ORDER_ASC);

            List<SearchHit> hits = index.search(
                            QUERY,
                            10,
                            0,
                            null,
                            false,
                            null,
                            SortOptions.sortedBy(spec),
                            List.of(ShardIndex.FIELD_CONTENT, "category"))
                    .getHits();

            assertEquals(List.of("low", "high"), ids(hits));
            assertTrue(hits.stream()
                    .allMatch(hit -> hit.getFields() == null || !hit.getFields().containsKey("price")));
            assertTrue(hits.stream().allMatch(hit -> "x".equals(hit.getFields().get("category"))));
            assertTrue(hits.stream().allMatch(hit -> hit.getContent() != null));
            assertTrue(hits.stream().allMatch(hit -> hit.getSortValues() != null));
        }
    }

    private ShardIndex index() {
        return new ShardIndex(SHARD_ID, tempDir, fieldConfigMap(), FAKE_EMBEDDER);
    }

    private static void write(ShardIndex index, SearchDocument... documents) throws IOException {
        for (SearchDocument document : documents) {
            index.index(document);
        }
        index.commit();
    }

    private static List<SearchHit> search(ShardIndex index, int limit, SortSpec spec) {
        return index.search(QUERY, limit, 0, null, false, null, SortOptions.sortedBy(spec))
                .getHits();
    }

    private static List<String> ids(List<SearchHit> hits) {
        return hits.stream().map(SearchHit::getDocId).toList();
    }

    private static SortSpec sort(String field, SortOrder order) {
        return SortSpec.effective(List.of(field(field, order)), false);
    }

    private static com.danieljhkim.dsearch.proto.common.SortField field(String name, SortOrder order) {
        return com.danieljhkim.dsearch.proto.common.SortField.newBuilder()
                .setField(name)
                .setOrder(order)
                .build();
    }

    private static SearchDocument doc(String id, double price, int year, String category) {
        return doc(id, price, year, category, QUERY + " content for " + id);
    }

    private static SearchDocument doc(String id, double price, int year, String category, String content) {
        Map<String, String> fields = new HashMap<>();
        fields.put(ShardIndex.FIELD_CONTENT, content);
        fields.put("price", Double.toString(price));
        fields.put("year", Integer.toString(year));
        fields.put("category", category);
        return new SearchDocument(id, fields);
    }

    private static SearchDocument docWithoutPrice(String id) {
        Map<String, String> fields = new HashMap<>();
        fields.put(ShardIndex.FIELD_CONTENT, QUERY + " content for " + id);
        fields.put("year", "1999");
        fields.put("category", "zeta");
        return new SearchDocument(id, fields);
    }

    private static Map<String, FieldConfig> fieldConfigMap() {
        Map<String, FieldConfig> configs = new HashMap<>();
        configs.put("price", fieldConfig("price", FieldType.DOUBLE, true));
        configs.put("year", fieldConfig("year", FieldType.INTEGER, true));
        configs.put("category", fieldConfig("category", FieldType.STRING, true));
        configs.put("body", fieldConfig("body", FieldType.STRING, false));
        return configs;
    }

    private static FieldConfig fieldConfig(String name, FieldType type, boolean sortable) {
        FieldConfig config = new FieldConfig();
        config.setName(name);
        config.setType(type);
        config.setFilterable(true);
        config.setSortable(sortable);
        return config;
    }
}
