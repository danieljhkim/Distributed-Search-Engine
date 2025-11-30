package com.dk.dsearch.querynode.search;

import com.dk.dsearch.common.model.SearchHit;

import java.util.Map;

public class Scorer {

    /**
     * Note: due to pagination, the min and max scores may not be the actual min and max in the full result set,
     * this results in uneven normalization scores across different pages for scoreSum, but the ranking should be consistent.
     */
    public static void normalizeScores(Map<String, SearchHit> map) {
        // Min-max normalization to [0,1] per list
        if (map.isEmpty()) return;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (SearchHit rs : map.values()) {
            min = Math.min(min, rs.getScore());
            max = Math.max(max, rs.getScore());
        }
        if (max == min) {
            // avoid div/0; just set all to 1.0
            for (SearchHit rs : map.values()) {
                rs.normScore = 1.0;
            }
            return;
        }
        for (SearchHit rs : map.values()) {
            rs.normScore = (rs.getScore() - min) / (max - min);
        }
    }

    //--------------------- Score fusion strategies ---------------------

    public static double scoreSum(SearchHit bm25, SearchHit sem) {
        double s1 = bm25 != null ? bm25.normScore : 0.0;
        double s2 = sem != null ? sem.normScore : 0.0;
        return s1 + s2;
    }

    public static double weighted(SearchHit bm25, SearchHit sem, double alpha, double beta) {
        double s1 = bm25 != null ? bm25.normScore : 0.0;
        double s2 = sem != null ? sem.normScore : 0.0;
        return alpha * s1 + beta * s2;
    }

    public static double rrf(SearchHit bm25, SearchHit sem, int k) {
        double r1 = bm25 != null ? 1.0 / (k + bm25.getRank() + 1) : 0.0;
        double r2 = sem != null ? 1.0 / (k + sem.getRank() + 1) : 0.0;
        return r1 + r2;
    }
}