package com.danieljhkim.dsearch.common.model;

import com.danieljhkim.dsearch.proto.common.SortValue;
import java.util.List;
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
    /**
     * Ordering tuple for this hit, positionally matched to the effective sort spec.
     *
     * <p>Null under relevance ordering. When a sort was requested this is what the distributed
     * merge compares and what a cursor is built from, so it travels with the hit rather than
     * being recomputed at the query node, which has no access to the shard's DocValues.
     */
    List<SortValue> sortValues;

    public SearchHit(String docId, String title, String content, float score) {
        this.docId = docId;
        this.score = score;
        this.content = content;
        this.title = title;
        this.highlightedFields = null;
        this.fields = null;
        this.sortValues = null;
    }

    public SearchHit(String docId, String title, String content, float score, Map<String, String> highlightedFields) {
        this.docId = docId;
        this.score = score;
        this.content = content;
        this.title = title;
        this.highlightedFields = highlightedFields != null ? Map.copyOf(highlightedFields) : null;
        this.fields = null;
        this.sortValues = null;
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
        this.sortValues = null;
    }

    public SearchHit(
            String docId,
            String title,
            String content,
            float score,
            Map<String, String> highlightedFields,
            Map<String, String> fields,
            List<SortValue> sortValues) {
        this.docId = docId;
        this.score = score;
        this.content = content;
        this.title = title;
        this.highlightedFields = highlightedFields != null ? Map.copyOf(highlightedFields) : null;
        this.fields = fields != null ? Map.copyOf(fields) : null;
        this.sortValues = sortValues != null ? List.copyOf(sortValues) : null;
    }
}
