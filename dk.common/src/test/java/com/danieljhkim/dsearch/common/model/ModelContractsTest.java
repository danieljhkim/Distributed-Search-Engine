package com.danieljhkim.dsearch.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.danieljhkim.dsearch.proto.common.FacetResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelContractsTest {

    @Test
    void mutableRequestModelsExposeTheirConfiguredValues() {
        Query query = new Query();
        query.setQueryString("hello");
        assertEquals("hello", query.getQueryString());

        Filter filter = new Filter("title", "search");
        assertEquals("title", filter.getField());
        assertEquals("search", filter.getValue());
        filter.setField("content");
        filter.setValue("text");
        assertEquals("Filter{field='content', value='text'}", filter.toString());

        Field field = new Field("title", "value");
        field.setName("content");
        field.setValue("body");
        assertEquals("Field{name='content', value='body'}", field.toString());
    }

    @Test
    void searchDocumentAndHitDefensivelyCopyMaps() {
        Map<String, String> fields = new java.util.HashMap<>();
        fields.put("title", "hello");
        SearchDocument document = new SearchDocument("doc-1", fields);
        fields.put("title", "changed");
        assertEquals("hello", document.getFields().get("title"));
        assertThrows(
                UnsupportedOperationException.class, () -> document.getFields().put("x", "y"));

        Map<String, String> highlighted = new java.util.HashMap<>();
        highlighted.put("title", "<b>hello</b>");
        SearchHit hit = new SearchHit("doc-1", "title", "content", 1.5f, highlighted, Map.of("lang", "en"));
        highlighted.put("title", "changed");
        assertEquals("<b>hello</b>", hit.getHighlightedFields().get("title"));
        assertEquals(Map.of("lang", "en"), hit.getFields());
        assertNull(new SearchHit("id", "title", "content", 0.5f).getFields());
        assertEquals(2.0f, hit.toBuilder().score(2.0f).build().getScore());
    }

    @Test
    void searchResultsTrackPagingFacetsAndFanoutStatus() {
        SearchHit hit = new SearchHit("doc", "title", "content", 1.0f);
        FacetResponse facet = FacetResponse.newBuilder().setField("category").build();
        SearchResult result = new SearchResult(List.of(hit), 8, 2, List.of(facet));
        assertEquals(1, result.getPage_size());
        assertEquals(2, result.getPage());
        assertIterableEquals(List.of(facet), result.getFacets());
        result.setPage(3);
        assertEquals(3, result.getPage());
        assertNull(new SearchResult(List.of(hit), 1).getFanoutMetadata());

        SearchResult.FanoutMetadata success = new SearchResult.FanoutMetadata(2, 2, 0, 0);
        SearchResult.FanoutMetadata partial = new SearchResult.FanoutMetadata(2, 1, 1, 0);
        SearchResult.FanoutMetadata failed = new SearchResult.FanoutMetadata(1, 0, 1, 0);
        assertEquals(SearchResult.FanoutStatus.SUCCESS, success.status());
        assertEquals(SearchResult.FanoutStatus.PARTIAL_FAILURE, partial.status());
        assertEquals(SearchResult.FanoutStatus.FAILED, failed.status());
        assertEquals(SearchResult.FanoutStatus.FAILED, new SearchResult.FanoutMetadata(0, 0, 0, 0).status());
        assertSame(success, SearchResult.FanoutMetadata.combine(success, null));
        assertSame(success, SearchResult.FanoutMetadata.combine(null, success));
        assertEquals(
                new SearchResult.FanoutMetadata(4, 3, 1, 1),
                SearchResult.FanoutMetadata.combine(partial, new SearchResult.FanoutMetadata(2, 2, 0, 1)));
    }
}
