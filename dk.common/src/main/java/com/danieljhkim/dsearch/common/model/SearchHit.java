package com.danieljhkim.dsearch.common.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class SearchHit {
    String docId;
    String content;
    String title;
    float score;

    public SearchHit(String docId, String title, String content, float score) {
        this.docId = docId;
        this.score = score;
        this.content = content;
        this.title = title;
    }
}