package com.danieljhkim.dsearch.common.schema;

import com.danieljhkim.dsearch.common.config.AppConfig.FieldConfig;
import com.danieljhkim.dsearch.common.enums.FieldType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FieldSchema(
        String name,
        FieldType type,
        boolean filterable,
        boolean sortable,
        boolean facetable,
        boolean highlightable,
        String analyzer) {

    public FieldSchema {
        Objects.requireNonNull(name, "name");
        type = type == null ? FieldType.STRING : type;
        analyzer = AnalyzerConfig.normalize(analyzer);
    }

    public static FieldSchema from(FieldConfig config) {
        Objects.requireNonNull(config, "config");
        FieldType type = config.getType() == null ? FieldType.STRING : config.getType();
        return new FieldSchema(
                config.getName(),
                type,
                config.isFilterable(),
                config.isSortable(),
                config.isFacetable(),
                config.isHighlightable(),
                AnalyzerConfig.normalize(config.getAnalyzer()));
    }
}
