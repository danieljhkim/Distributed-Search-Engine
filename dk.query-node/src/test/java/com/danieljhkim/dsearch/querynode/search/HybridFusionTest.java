package com.danieljhkim.dsearch.querynode.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.common.model.SearchHit;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.proto.common.FusionStrategy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HybridFusionTest {

    @Test
    void scoreSumRanksOverlappingAndOneSidedDocumentsAndAppliesLimit() {
        List<SearchHit> fused = HybridFusion.fuse(
                result(hit("a", 10), hit("b", 5), hit("c", 1)),
                result(hit("b", 10), hit("d", 7), hit("e", 5)),
                FusionStrategy.SCORE_SUM,
                3,
                0.5,
                0.5);

        assertEquals(List.of("b", "a", "d"), docIds(fused));
        assertTrue(fused.get(0).getScore() > fused.get(1).getScore());
        assertTrue(fused.get(1).getScore() > fused.get(2).getScore());
    }

    @Test
    void weightedUsesTheConfiguredBm25AndSemanticWeights() {
        List<SearchHit> fused = HybridFusion.fuse(
                result(hit("a", 10), hit("b", 5), hit("c", 1)),
                result(hit("b", 10), hit("d", 7), hit("e", 5)),
                FusionStrategy.WEIGHTED,
                10,
                0.8,
                0.2);

        assertEquals(List.of("a", "b", "d", "c", "e"), docIds(fused));
        assertEquals(0.8f, fused.get(0).getScore(), 0.0001f);
        assertEquals(0.8f * (4.0f / 9.0f) + 0.2f, fused.get(1).getScore(), 0.0001f);
        assertEquals(0.2f * (2.0f / 5.0f), fused.get(2).getScore(), 0.0001f);
    }

    @Test
    void rrfUsesRanksAndTheConfiguredK() {
        List<SearchHit> fused = HybridFusion.fuse(
                result(hit("a", 10), hit("b", 5), hit("c", 1)),
                result(hit("b", 10), hit("d", 7), hit("e", 5)),
                FusionStrategy.RRF,
                3,
                0.5,
                0.5,
                1);

        assertEquals(List.of("b", "a", "d"), docIds(fused));
        assertEquals((float) (1.0 / 2 + 1.0 / 3), fused.get(0).getScore(), 0.0001f);
        assertEquals((float) (1.0 / 2), fused.get(1).getScore(), 0.0001f);
        assertEquals((float) (1.0 / 3), fused.get(2).getScore(), 0.0001f);
    }

    @Test
    void prefersBm25MetadataWhenAHitIsPresentInBothLists() {
        SearchHit bm25Hit = new SearchHit(
                "shared",
                "BM25 title",
                "BM25 content",
                10,
                Map.of("content", "BM25 highlight"),
                Map.of("source", "bm25"));
        SearchHit semanticHit = new SearchHit(
                "shared",
                "semantic title",
                "semantic content",
                10,
                Map.of("content", "semantic highlight"),
                Map.of("source", "semantic"));

        SearchHit fused = HybridFusion.fuse(
                        result(bm25Hit), result(semanticHit), FusionStrategy.SCORE_SUM, 10, 0.5, 0.5)
                .getFirst();

        assertEquals("BM25 title", fused.getTitle());
        assertEquals("BM25 content", fused.getContent());
        assertEquals(Map.of("source", "bm25"), fused.getFields());
        assertEquals(Map.of("content", "BM25 highlight"), fused.getHighlightedFields());
    }

    @Test
    void handlesNullAndEmptyResultsAndAZeroLimit() {
        assertTrue(HybridFusion.fuse(null, null, FusionStrategy.RRF, 10, 0.5, 0.5).isEmpty());
        assertTrue(HybridFusion.fuse(new SearchResult(List.of(), 0), null, FusionStrategy.RRF, 10, 0.5, 0.5)
                .isEmpty());

        List<SearchHit> fused = HybridFusion.fuse(
                result(hit("a", 2), hit("b", 1)),
                null,
                FusionStrategy.SCORE_SUM,
                0,
                0.5,
                0.5);

        assertEquals(List.of("a", "b"), docIds(fused));
    }

    @Test
    void skipsHitsWithoutDocumentIds() {
        List<SearchHit> fused = HybridFusion.fuse(
                result(new SearchHit(null, "ignored", "ignored", 100), hit("valid", 1)),
                result(hit("other", 1)),
                FusionStrategy.SCORE_SUM,
                10,
                0.5,
                0.5);

        assertEquals(List.of("valid", "other"), docIds(fused));
        assertTrue(fused.stream().allMatch(hit -> hit.getDocId() != null));
    }

    private static SearchResult result(SearchHit... hits) {
        return new SearchResult(List.of(hits), hits.length, 0);
    }

    private static SearchHit hit(String docId, float score) {
        return new SearchHit(docId, "title-" + docId, "content-" + docId, score);
    }

    private static List<String> docIds(List<SearchHit> hits) {
        return hits.stream().map(SearchHit::getDocId).toList();
    }
}
