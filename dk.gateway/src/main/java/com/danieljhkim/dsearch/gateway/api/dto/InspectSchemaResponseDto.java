package com.danieljhkim.dsearch.gateway.api.dto;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InspectSchemaResponseDto {
    private String auditId;
    private String indexName;
    private String alias;
    private int compatibilityVersion;
    private String analyzer;
    private List<FieldSchemaDto> fields;
    private EmbeddingIdentityDto embedding;

    @Getter
    @Setter
    public static class FieldSchemaDto {
        private String name;
        private String type;
        private boolean filterable;
        private boolean sortable;
        private boolean facetable;
        private boolean highlightable;
        private String analyzer;
    }

    @Getter
    @Setter
    public static class EmbeddingIdentityDto {
        private String modelId;
        private String engine;
        private String digest;
        private int dimension;
    }
}
