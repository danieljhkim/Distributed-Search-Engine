package com.danieljhkim.dsearch.indexnode.index.facet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.proto.common.FacetRequest;
import com.danieljhkim.dsearch.proto.common.FacetResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FacetCalculatorTest {

    private final FacetCalculator facetCalculator = new FacetCalculator();

    private Directory directory;
    private IndexReader reader;
    private IndexSearcher searcher;

    @AfterEach
    void tearDown() throws IOException {
        if (reader != null) {
            reader.close();
        }
        if (directory != null) {
            directory.close();
        }
    }

    @Test
    void computeFacetsReturnsEmptyListForNullRequests() throws IOException {
        buildIndex(List.of());

        List<FacetResponse> responses = facetCalculator.computeFacets(searcher, new MatchAllDocsQuery(), null);

        assertTrue(responses.isEmpty());
    }

    @Test
    void computeFacetsReturnsEmptyListForEmptyRequests() throws IOException {
        buildIndex(List.of());

        List<FacetResponse> responses = facetCalculator.computeFacets(searcher, new MatchAllDocsQuery(), List.of());

        assertTrue(responses.isEmpty());
    }

    @Test
    void computeFacetsReturnsBucketsWithCorrectCounts() throws IOException {
        buildIndex(List.of(
                Map.of("category", "books"),
                Map.of("category", "books"),
                Map.of("category", "movies"),
                Map.of("category", "music")));

        FacetRequest request = FacetRequest.newBuilder().setField("category").build();
        List<FacetResponse> responses =
                facetCalculator.computeFacets(searcher, new MatchAllDocsQuery(), List.of(request));

        assertEquals(1, responses.size());
        FacetResponse response = responses.get(0);
        assertEquals("category", response.getField());

        Map<String, Long> counts =
                response.getBucketsList().stream().collect(Collectors.toMap(b -> b.getValue(), b -> b.getCount()));
        assertEquals(3, counts.size());
        assertEquals(2L, counts.get("books"));
        assertEquals(1L, counts.get("movies"));
        assertEquals(1L, counts.get("music"));
    }

    @Test
    void computeFacetsRespectsSizeSmallerThanDistinctValues() throws IOException {
        buildIndex(List.of(
                Map.of("category", "a"),
                Map.of("category", "b"),
                Map.of("category", "b"),
                Map.of("category", "c"),
                Map.of("category", "c"),
                Map.of("category", "c")));

        FacetRequest request =
                FacetRequest.newBuilder().setField("category").setSize(1).build();
        List<FacetResponse> responses =
                facetCalculator.computeFacets(searcher, new MatchAllDocsQuery(), List.of(request));

        assertEquals(1, responses.size());
        FacetResponse response = responses.get(0);
        assertEquals(1, response.getBucketsCount());
        assertEquals("c", response.getBuckets(0).getValue());
        assertEquals(3L, response.getBuckets(0).getCount());
    }

    @Test
    void computeFacetsOnIndexWithNoFacetFieldsDoesNotThrow() throws IOException {
        directory = new ByteBuffersDirectory();
        IndexWriterConfig config = new IndexWriterConfig();
        try (IndexWriter writer = new IndexWriter(directory, config)) {
            Document doc = new Document();
            doc.add(new StringField("title", "hello", Field.Store.YES));
            writer.addDocument(doc);
        }
        reader = DirectoryReader.open(directory);
        searcher = new IndexSearcher(reader);

        FacetRequest request = FacetRequest.newBuilder().setField("category").build();
        List<FacetResponse> responses =
                facetCalculator.computeFacets(searcher, new MatchAllDocsQuery(), List.of(request));

        assertTrue(responses.isEmpty());
    }

    private void buildIndex(List<Map<String, String>> docsFacetValues) throws IOException {
        directory = new ByteBuffersDirectory();
        FacetsConfig facetsConfig = facetCalculator.getFacetsConfig();
        IndexWriterConfig config = new IndexWriterConfig();
        try (IndexWriter writer = new IndexWriter(directory, config)) {
            for (Map<String, String> facetValues : docsFacetValues) {
                Document doc = new Document();
                for (Map.Entry<String, String> entry : facetValues.entrySet()) {
                    doc.add(new SortedSetDocValuesFacetField(entry.getKey(), entry.getValue()));
                }
                writer.addDocument(facetsConfig.build(doc));
            }
        }
        reader = DirectoryReader.open(directory);
        searcher = new IndexSearcher(reader);
    }
}
