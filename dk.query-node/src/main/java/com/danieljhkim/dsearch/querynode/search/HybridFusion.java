package com.danieljhkim.dsearch.querynode.search;

import com.danieljhkim.dsearch.common.model.SearchHit;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.proto.common.FusionStrategy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class HybridFusion {

    private HybridFusion() {}

    public static List<SearchHit> fuse(
            SearchResult bm25Result,
            SearchResult semanticResult,
            FusionStrategy strategy,
            int limit,
            double alpha,
            double beta) {
        return fuse(bm25Result, semanticResult, strategy, limit, alpha, beta, 60);
    }

    /**
     * Main fusion method.
     *
     * @param bm25Result
     *            lexical (BM25) result
     * @param semanticResult
     *            dense/semantic result
     * @param strategy
     *            fusion strategy (SCORE_SUM, WEIGHTED, RRF, ...)
     * @param limit
     *            max number of fused hits to return
     * @param alpha
     *            weight for BM25 (for WEIGHTED)
     * @param beta
     *            weight for semantic (for WEIGHTED)
     * @param rrfK
     *            RRF parameter k
     */
    @SuppressWarnings("all")
    public static List<SearchHit> fuse(
            SearchResult bm25Result,
            SearchResult semanticResult,
            FusionStrategy strategy,
            int limit,
            double alpha,
            double beta,
            int rrfK) {
        List<SearchHit> bm25Hits =
                bm25Result != null && bm25Result.getHits() != null ? bm25Result.getHits() : List.of();
        List<SearchHit> semHits =
                semanticResult != null && semanticResult.getHits() != null ? semanticResult.getHits() : List.of();

        // docId -> RankedScore for each list
        Map<String, RankedScore> bm25Map = toRankedMap(bm25Hits);
        Map<String, RankedScore> semMap = toRankedMap(semHits);

        // Union of docIds across both lists
        Set<String> allDocIds = new LinkedHashSet<>(bm25Map.keySet());
        allDocIds.addAll(semMap.keySet());

        // Normalize scores independently per list to [0,1]
        normalizeScores(bm25Map);
        normalizeScores(semMap);

        List<SearchHit> fused = new ArrayList<>(allDocIds.size());
        for (String docId : allDocIds) {
            RankedScore bm25 = bm25Map.get(docId);
            RankedScore sem = semMap.get(docId);

            double fusedScore =
                    switch (strategy) {
                        case SCORE_SUM -> scoreSum(bm25, sem);
                        case WEIGHTED -> weighted(bm25, sem, alpha, beta);
                        case RRF -> rrf(bm25, sem, rrfK);
                        default -> rrf(bm25, sem, rrfK);
                    };

            // Prefer BM25 metadata, fall back to semantic
            String title = bm25 != null ? bm25.title : (sem != null ? sem.title : null);
            String content = bm25 != null ? bm25.content : (sem != null ? sem.content : null);
            Map<String, String> fields = bm25 != null ? bm25.fields : (sem != null ? sem.fields : null);
            Map<String, String> highlightedFields =
                    bm25 != null ? bm25.highlightedFields : (sem != null ? sem.highlightedFields : null);

            fused.add(new SearchHit(docId, title, content, (float) fusedScore, highlightedFields, fields));
        }

        // Score ties are common for rank-based lexical input and RRF. The stable
        // input-rank order (lexical first, then semantic-only hits) is retained.
        fused.sort(Comparator.comparingDouble(SearchHit::getScore).reversed());

        int safeLimit = Math.max(limit, 0);
        if (safeLimit == 0 || fused.isEmpty()) {
            return fused;
        }
        if (safeLimit < fused.size()) {
            return fused.subList(0, safeLimit);
        }
        return fused;
    }

    // -------------------- internal helpers --------------------

    /**
     * Note: due to pagination, the min and max scores may not be the actual min and
     * max in the full result set,
     * this results in uneven normalization scores across different pages for
     * scoreSum, but the ranking should be consistent.
     */
    private static void normalizeScores(Map<String, RankedScore> map) {
        if (map == null || map.isEmpty()) return;

        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (RankedScore rs : map.values()) {
            min = Math.min(min, rs.rawScore);
            max = Math.max(max, rs.rawScore);
        }
        if (max == min) {
            // No variance → treat all as max
            for (RankedScore rs : map.values()) {
                rs.normScore = 1.0;
            }
            return;
        }
        double range = max - min;
        for (RankedScore rs : map.values()) {
            rs.normScore = (rs.rawScore - min) / range;
        }
    }

    private static Map<String, RankedScore> toRankedMap(List<SearchHit> hits) {
        Map<String, RankedScore> map = new LinkedHashMap<>();
        if (hits == null) return map;
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            if (h == null || h.getDocId() == null) continue;
            map.put(
                    h.getDocId(),
                    new RankedScore(
                            h.getScore(), i, h.getTitle(), h.getContent(), h.getFields(), h.getHighlightedFields()));
        }
        return map;
    }

    private static double scoreSum(RankedScore bm25, RankedScore sem) {
        double s1 = bm25 != null ? bm25.normScore : 0.0;
        double s2 = sem != null ? sem.normScore : 0.0;
        return s1 + s2;
    }

    // --------------------- Score fusion strategies ---------------------

    private static double weighted(RankedScore bm25, RankedScore sem, double alpha, double beta) {
        double s1 = bm25 != null ? bm25.normScore : 0.0;
        double s2 = sem != null ? sem.normScore : 0.0;
        return alpha * s1 + beta * s2;
    }

    private static double rrf(RankedScore bm25, RankedScore sem, int k) {
        double r1 = bm25 != null ? 1.0 / (k + bm25.rank + 1) : 0.0;
        double r2 = sem != null ? 1.0 / (k + sem.rank + 1) : 0.0;
        return r1 + r2;
    }

    private static final class RankedScore {
        final double rawScore;
        final int rank; // 0-based rank inside its own list
        final String title;
        final String content;
        double normScore;
        Map<String, String> fields;
        Map<String, String> highlightedFields;

        RankedScore(
                double rawScore,
                int rank,
                String title,
                String content,
                Map<String, String> fields,
                Map<String, String> highlightedFields) {
            this.rawScore = rawScore;
            this.rank = rank;
            this.title = title;
            this.content = content;
            this.normScore = rawScore;
            this.fields = fields;
            this.highlightedFields = highlightedFields;
        }
    }
}
