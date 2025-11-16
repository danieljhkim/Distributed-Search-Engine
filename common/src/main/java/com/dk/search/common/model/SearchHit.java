package com.dk.search.common.model;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SearchHit {
    private final String docId;
    private final double score;

    public SearchHit(String docId, double score) {
        this.docId = docId;
        this.score = score;
    }

}