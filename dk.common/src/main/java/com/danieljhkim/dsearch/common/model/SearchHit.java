package com.danieljhkim.dsearch.common.model;

import java.util.Map;
import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class SearchHit {
    String docId;
    String content;
    String title;
    float score;
    Map<String, String> highlightedFields;
    Map<String, String> fields;

    public SearchHit(String docId, String title, String content, float score) {
        this.docId = docId;
        this.score = score;
        this.content = content;
        this.title = title;
        this.highlightedFields = null;
        this.fields = null;
    }

    public SearchHit(String docId, String title, String content, float score, Map<String, String> highlightedFields) {
        this.docId = docId;
        this.score = score;
        this.content = content;
        this.title = title;
        this.highlightedFields = highlightedFields != null ? Map.copyOf(highlightedFields) : null;
        this.fields = null;
    }

    public SearchHit(
            String docId,
            String title,
            String content,
            float score,
            Map<String, String> highlightedFields,
            Map<String, String> fields) {
        this.docId = docId;
        this.score = score;
        this.content = content;
        this.title = title;
        this.highlightedFields = highlightedFields != null ? Map.copyOf(highlightedFields) : null;
        this.fields = fields != null ? Map.copyOf(fields) : null;
    }
}
