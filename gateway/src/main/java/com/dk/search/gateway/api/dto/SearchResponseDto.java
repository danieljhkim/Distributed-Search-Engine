package com.dk.search.gateway.api.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class SearchResponseDto {

    private List<SearchHitDto> hits;
    private long totalHits;
    private long tookMillis;

    public SearchResponseDto() {
    }

    public SearchResponseDto(List<SearchHitDto> hits, long totalHits, long tookMillis) {
        this.hits = hits;
        this.totalHits = totalHits;
        this.tookMillis = tookMillis;
    }

    @Setter
    @Getter
    public static class SearchHitDto {
        private String docId;
        private double score;

        public SearchHitDto() {
        }

        public SearchHitDto(String docId, double score) {
            this.docId = docId;
            this.score = score;
        }

    }
}