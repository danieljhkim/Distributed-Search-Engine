package com.dk.dsearch.querynode.search;


import com.dk.dsearch.common.model.SearchHit;
import com.dk.dsearch.common.model.SearchResult;
import com.dk.dsearch.common.enums.HybridFusionStrategy;

import java.util.*;
import java.util.stream.Collectors;

public final class HybridFusion {

    private HybridFusion() {}

    public static List<SearchHit> fuse(
            SearchResult bm25Result,
            SearchResult semanticResult,
            HybridFusionStrategy strategy,
            int limit,
            double alpha,  // for WEIGHTED
            double beta    // for WEIGHTED
    ) {
        List<SearchHit> bm25Hits = bm25Result.getHits();
        List<SearchHit> semHits = semanticResult.getHits();

        // Build maps: docId -> (score, rank)
        Map<String, SearchHit> bm25Map = toRankedMap(bm25Hits);
        Map<String, SearchHit> semMap = toRankedMap(semHits);

        Set<String> allDocIds = new HashSet<>();
        allDocIds.addAll(bm25Map.keySet());
        allDocIds.addAll(semMap.keySet());

        Scorer.normalizeScores(bm25Map);
        Scorer.normalizeScores(semMap);

        List<FusedHit> fused = new ArrayList<>();
        for (String docId : allDocIds) {
            SearchHit bm25 = bm25Map.get(docId);
            SearchHit sem = semMap.get(docId);

            double fusedScore = switch (strategy) {
                case SCORE_SUM -> Scorer.scoreSum(bm25, sem);
                case WEIGHTED  -> Scorer.weighted(bm25, sem, alpha, beta);
                case RRF       -> Scorer.rrf(bm25, sem, 60); // k=60 is typical
            };

            // Pick a representative title/content from whichever hit is present
            String title = bm25 != null ? bm25.getTitle() : (sem != null ? sem.getTitle() : null);
            String content = bm25 != null ? bm25.getContent() : (sem != null ? sem.getContent() : null);
            fused.add(new FusedHit(docId, title, content, fusedScore));
        }

        fused.sort(Comparator.comparingDouble(FusedHit::score).reversed()); //TODO: optimize with a heap
        return fused.stream()
                .limit(limit)
                .map(h -> new SearchHit(h.docId, h.title, h.content, (float) h.score))
                .collect(Collectors.toList());
    }

    public static Map<String, SearchHit> toRankedMap(List<SearchHit> hits) {
        Map<String, SearchHit> map = new HashMap<>();
        for (SearchHit h : hits) {
            map.put(h.getDocId(), h);
        }
        return map;
    }

    private record FusedHit(String docId, String title, String content, double score) {}
}