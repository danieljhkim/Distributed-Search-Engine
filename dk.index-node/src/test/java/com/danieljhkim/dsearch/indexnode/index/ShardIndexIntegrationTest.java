package com.danieljhkim.dsearch.indexnode.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.common.config.AppConfig.FieldConfig;
import com.danieljhkim.dsearch.common.enums.FieldType;
import com.danieljhkim.dsearch.common.model.SearchDocument;
import com.danieljhkim.dsearch.common.model.SearchHit;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.ml.embedding.TextEmbedder;
import com.danieljhkim.dsearch.proto.common.FacetBucket;
import com.danieljhkim.dsearch.proto.common.FacetRequest;
import com.danieljhkim.dsearch.proto.common.FacetResponse;
import com.danieljhkim.dsearch.proto.common.Filter;
import com.danieljhkim.dsearch.proto.common.FilterOperator;
import com.danieljhkim.dsearch.proto.common.SearchType;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShardIndexIntegrationTest {

    private static final String SHARD_ID = "0";
    private static final TextEmbedder FAKE_EMBEDDER = ignored -> new float[] {1.0f, 0.0f, 0.0f};

    @TempDir
    Path tempDir;

    @Test
    void commitControlsVisibilityAndReopenPersistsDocuments() throws IOException {
        try (ShardIndex index = new ShardIndex(SHARD_ID, tempDir, fieldConfigMap(), FAKE_EMBEDDER)) {
            index.index(document(
                    "doc-visible",
                    "Commit Visibility",
                    "violet searchable content becomes visible only after commit",
                    Map.of("category", "guide", "year", "2024")));

            assertSearch(index, "violet", 0, List.of());

            index.commit();

            assertSearch(index, "violet", 1, List.of("doc-visible"));
        }

        try (ShardIndex reopened = new ShardIndex(SHARD_ID, tempDir, fieldConfigMap(), FAKE_EMBEDDER)) {
            assertSearch(reopened, "violet", 1, List.of("doc-visible"));
        }
    }

    @Test
    void exactGetUsesTheIdTermAndReturnsStoredFields() throws IOException {
        try (ShardIndex index = new ShardIndex(SHARD_ID, tempDir, fieldConfigMap(), FAKE_EMBEDDER)) {
            String exactId = "doc:[* TO *]";
            index.index(document(exactId, "Exact title", "exact stored content", Map.of("category", "guide")));
            index.commit();

            SearchDocument retrieved = index.get(exactId);
            assertNotNull(retrieved);
            assertEquals(exactId, retrieved.getId());
            assertEquals("Exact title", retrieved.getFields().get("title"));
            assertEquals("guide", retrieved.getFields().get("category"));
            assertEquals(null, index.get("doc:*"));
        }
    }

    @Test
    void bm25FiltersPaginationFacetsAndHighlightsUseDeterministicFixtures() throws IOException {
        try (ShardIndex index = new ShardIndex(SHARD_ID, tempDir, fieldConfigMap(), FAKE_EMBEDDER)) {
            indexFixtureDocuments(index);
            index.commit();

            SearchResult bm25 = index.search("luminous", 10, 0);
            assertEquals(1, bm25.getTotalHits());
            assertEquals(List.of("doc-atlas"), hitIds(bm25));

            SearchResult exactFilter = index.search(
                    "searchable", 10, 0, List.of(filter("category", FilterOperator.EQ, "guide")), false, null);
            assertEquals(2, exactFilter.getTotalHits());
            assertEquals(Set.of("doc-atlas", "doc-cinder"), Set.copyOf(hitIds(exactFilter)));

            SearchResult numericFilter =
                    index.search("searchable", 10, 0, List.of(filter("year", FilterOperator.GTE, "2021")), false, null);
            assertEquals(2, numericFilter.getTotalHits());
            assertEquals(Set.of("doc-atlas", "doc-cinder"), Set.copyOf(hitIds(numericFilter)));

            SearchResult firstPage = index.search("searchable", 2, 0);
            assertEquals(4, firstPage.getTotalHits());
            assertEquals(2, firstPage.getHits().size());

            SearchResult secondPage = index.search("searchable", 2, 2);
            assertEquals(4, secondPage.getTotalHits());
            assertEquals(2, secondPage.getHits().size());

            SearchResult emptyPage = index.search("searchable", 2, 4);
            assertEquals(4, emptyPage.getTotalHits());
            assertEquals(List.of(), emptyPage.getHits());

            SearchResult faceted = index.search(
                    "searchable",
                    10,
                    0,
                    null,
                    false,
                    List.of(FacetRequest.newBuilder()
                            .setField("category")
                            .setSize(10)
                            .build()));
            assertEquals(Map.of("guide", 2L, "reference", 1L, "news", 1L), facetCounts(faceted, "category"));

            SearchResult highlighted = index.search("luminous", 10, 0, null, true, null);
            SearchHit hit = highlighted.getHits().get(0);
            assertNotNull(hit.getHighlightedFields());
            assertTrue(hit.getHighlightedFields().getOrDefault("content", "").contains("<em>luminous</em>"));
        }
    }

    @Test
    void sortableConfiguredFieldsCanDriveLuceneSortOrder() throws IOException {
        try (ShardIndex index = new ShardIndex(SHARD_ID, tempDir, fieldConfigMap(), FAKE_EMBEDDER)) {
            indexFixtureDocuments(index);
            index.commit();
        }

        assertEquals(
                List.of("doc-atlas", "doc-cinder", "doc-boreal", "doc-delta"),
                sortedDocIds(new Sort(new SortField("year", SortField.Type.INT, true))));
    }

    @Test
    void reindexAndDeleteReplaceCommittedLifecycleState() throws IOException {
        try (ShardIndex index = new ShardIndex(SHARD_ID, tempDir, fieldConfigMap(), FAKE_EMBEDDER)) {
            index.index(document(
                    "doc-cycle",
                    "Lifecycle Original",
                    "amber original searchable content",
                    Map.of("category", "guide", "year", "2020")));
            index.commit();

            assertSearch(index, "amber", 1, List.of("doc-cycle"));

            index.index(document(
                    "doc-cycle",
                    "Lifecycle Replacement",
                    "cerulean replacement searchable content",
                    Map.of("category", "reference", "year", "2025")));
            index.commit();

            assertSearch(index, "amber", 0, List.of());
            assertSearch(index, "cerulean", 1, List.of("doc-cycle"));

            index.delete("doc-cycle");
            index.commit();

            assertSearch(index, "cerulean", 0, List.of());
        }
    }

    @Test
    void emptyIndexAndMissingShardReturnEmptyResults() throws IOException {
        try (ShardIndex emptyIndex = new ShardIndex(SHARD_ID, tempDir, fieldConfigMap(), FAKE_EMBEDDER)) {
            SearchResult emptyResult = emptyIndex.search("anything", 10, 0);
            assertEquals(0, emptyResult.getTotalHits());
            assertEquals(List.of(), emptyResult.getHits());
        }

        try (IndexManager manager =
                new IndexManager(tempDir.resolve("manager"), 10, Duration.ofHours(1), fieldConfigs(), FAKE_EMBEDDER)) {
            SearchResult missingShard = manager.searchDocument("missing-shard", "anything", 10, 0, SearchType.BM25);
            assertEquals(0, missingShard.getTotalHits());
            assertEquals(List.of(), missingShard.getHits());
        }
    }

    private void indexFixtureDocuments(ShardIndex index) throws IOException {
        for (SearchDocument document : fixtureDocuments()) {
            index.index(document);
        }
    }

    private List<String> sortedDocIds(Sort sort) throws IOException {
        Path shardPath = tempDir.resolve("shard-" + SHARD_ID);
        try (Directory directory = FSDirectory.open(shardPath);
                DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            TopDocs topDocs = searcher.search(new MatchAllDocsQuery(), 10, sort);

            List<String> ids = new ArrayList<>();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                ids.add(searcher.storedFields().document(scoreDoc.doc).get(ShardIndex.FIELD_ID));
            }
            return ids;
        }
    }

    private static void assertSearch(ShardIndex index, String query, long totalHits, List<String> docIds) {
        SearchResult result = index.search(query, 10, 0);
        assertEquals(totalHits, result.getTotalHits());
        assertEquals(docIds, hitIds(result));
    }

    private static List<SearchDocument> fixtureDocuments() {
        return List.of(
                document(
                        "doc-atlas",
                        "Atlas Search Guide",
                        "luminous searchable atlas guide covers ranking filters and highlights",
                        Map.of("category", "guide", "year", "2024")),
                document(
                        "doc-boreal",
                        "Boreal Search Reference",
                        "searchable boreal reference discusses facets and exact filters",
                        Map.of("category", "reference", "year", "2020")),
                document(
                        "doc-cinder",
                        "Cinder Search Guide",
                        "searchable cinder guide explains deletion and reindex lifecycle",
                        Map.of("category", "guide", "year", "2022")),
                document(
                        "doc-delta",
                        "Delta Search News",
                        "searchable delta bulletin talks about reopen and commit visibility",
                        Map.of("category", "news", "year", "2018")));
    }

    private static SearchDocument document(String id, String title, String content, Map<String, String> extraFields) {
        Map<String, String> fields = new HashMap<>(extraFields);
        fields.put(ShardIndex.FIELD_TITLE, title);
        fields.put(ShardIndex.FIELD_CONTENT, content);
        return new SearchDocument(id, fields);
    }

    private static List<String> hitIds(SearchResult result) {
        return result.getHits().stream().map(SearchHit::getDocId).toList();
    }

    private static Filter filter(String field, FilterOperator operator, String... values) {
        return Filter.newBuilder()
                .setField(field)
                .setOperator(operator)
                .addAllValues(List.of(values))
                .build();
    }

    private static Map<String, Long> facetCounts(SearchResult result, String field) {
        assertNotNull(result.getFacets());
        FacetResponse response = result.getFacets().stream()
                .filter(facet -> field.equals(facet.getField()))
                .findFirst()
                .orElseThrow();
        return response.getBucketsList().stream()
                .collect(Collectors.toMap(FacetBucket::getValue, FacetBucket::getCount));
    }

    private static List<FieldConfig> fieldConfigs() {
        return List.of(
                fieldConfig("category", FieldType.STRING, true, false, true),
                fieldConfig("year", FieldType.INTEGER, true, true, true));
    }

    private static Map<String, FieldConfig> fieldConfigMap() {
        return fieldConfigs().stream().collect(Collectors.toMap(FieldConfig::getName, Function.identity()));
    }

    private static FieldConfig fieldConfig(
            String name, FieldType type, boolean filterable, boolean sortable, boolean facetable) {
        FieldConfig config = new FieldConfig();
        config.setName(name);
        config.setType(type);
        config.setFilterable(filterable);
        config.setSortable(sortable);
        config.setFacetable(facetable);
        return config;
    }
}
