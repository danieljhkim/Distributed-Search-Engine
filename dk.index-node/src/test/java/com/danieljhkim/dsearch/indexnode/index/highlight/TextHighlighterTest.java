package com.danieljhkim.dsearch.indexnode.index.highlight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.Test;

class TextHighlighterTest {

    private final TextHighlighter highlighter = new TextHighlighter();

    @Test
    void highlightReturnsEmptyMapForNullQuery() {
        Map<String, String> result = highlighter.highlight(null, Map.of("content", "some text"), Set.of("content"));

        assertTrue(result.isEmpty());
    }

    @Test
    void highlightReturnsEmptyMapForNullFieldContents() {
        Query query = new TermQuery(new Term("content", "text"));

        Map<String, String> result = highlighter.highlight(query, null, Set.of("content"));

        assertTrue(result.isEmpty());
    }

    @Test
    void highlightReturnsEmptyMapForEmptyFieldContents() {
        Query query = new TermQuery(new Term("content", "text"));

        Map<String, String> result = highlighter.highlight(query, Map.of(), Set.of("content"));

        assertTrue(result.isEmpty());
    }

    @Test
    void highlightReturnsEmptyMapForEmptyFieldsToHighlight() {
        Query query = new TermQuery(new Term("content", "text"));

        Map<String, String> result = highlighter.highlight(query, Map.of("content", "some text"), Set.of());

        assertTrue(result.isEmpty());
    }

    @Test
    void highlightWrapsMatchingTermWithDefaultTags() {
        Query query = new TermQuery(new Term("content", "fox"));
        Map<String, String> fieldContents = Map.of("content", "the quick brown fox jumps over the lazy dog");

        Map<String, String> result = highlighter.highlight(query, fieldContents, Set.of("content"));

        assertTrue(result.containsKey("content"));
        assertTrue(result.get("content").contains("<em>fox</em>"));
    }

    @Test
    void highlightWrapsMatchingTermWithCustomTags() {
        TextHighlighter customHighlighter = new TextHighlighter("[[", "]]", 200, 3);
        Query query = new TermQuery(new Term("content", "fox"));
        Map<String, String> fieldContents = Map.of("content", "the quick brown fox jumps over the lazy dog");

        Map<String, String> result = customHighlighter.highlight(query, fieldContents, Set.of("content"));

        assertEquals("the quick brown [[fox]] jumps over the lazy dog", result.get("content"));
    }

    @Test
    void highlightFieldReturnsNullForNullContent() {
        Query query = new TermQuery(new Term("content", "fox"));

        assertNull(highlighter.highlightField(query, "content", null));
    }

    @Test
    void highlightFieldReturnsNullForEmptyContent() {
        Query query = new TermQuery(new Term("content", "fox"));

        assertNull(highlighter.highlightField(query, "content", ""));
    }

    @Test
    void highlightFieldReturnsNullForNonMatchingQuery() {
        Query query = new TermQuery(new Term("content", "zebra"));

        assertNull(highlighter.highlightField(query, "content", "the quick brown fox jumps over the lazy dog"));
    }

    @Test
    void highlightFieldReturnsHighlightedContentForMatchingQuery() {
        Query query = new TermQuery(new Term("content", "fox"));

        String result = highlighter.highlightField(query, "content", "the quick brown fox jumps over the lazy dog");

        assertEquals("the quick brown <em>fox</em> jumps over the lazy dog", result);
    }
}
