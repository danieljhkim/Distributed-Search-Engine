package com.dk.search.common.model;

import lombok.Getter;

import java.util.List;

@Getter
public class SearchResult {

    private final List<SearchHit> hits;
    private final long totalHits;
    private final long tookMillis;

    public SearchResult(List<SearchHit> hits, long totalHits, long tookMillis) {
        this.hits = List.copyOf(hits);
        this.totalHits = totalHits;
        this.tookMillis = tookMillis;
    }

    @Getter
    public static class SearchHit {
        private final String docId;
        private final double score;

        public SearchHit(String docId, double score) {
            this.docId = docId;
            this.score = score;
        }

    }
}