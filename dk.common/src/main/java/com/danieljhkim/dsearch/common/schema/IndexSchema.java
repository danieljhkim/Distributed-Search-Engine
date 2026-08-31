package com.danieljhkim.dsearch.common.schema;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.config.AppConfig.FieldConfig;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record IndexSchema(
        int compatibilityVersion,
        AnalyzerConfig analyzer,
        List<FieldSchema> fields,
        EmbeddingModelIdentity embedding) {

    public static final int CURRENT_COMPATIBILITY_VERSION = 1;

    public IndexSchema {
        if (compatibilityVersion < 1) {
            throw new IllegalArgumentException("compatibilityVersion must be >= 1");
        }
        analyzer = analyzer == null ? AnalyzerConfig.standard() : analyzer;
        fields = List.copyOf(sorted(fields == null ? List.of() : fields));
        Objects.requireNonNull(embedding, "embedding");
    }

    public static IndexSchema current(
            AnalyzerConfig analyzer, List<FieldSchema> fields, EmbeddingModelIdentity embedding) {
        return new IndexSchema(CURRENT_COMPATIBILITY_VERSION, analyzer, fields, embedding);
    }

    public static IndexSchema fromAppConfig(AppConfig appConfig, EmbeddingModelIdentity embedding) {
        Objects.requireNonNull(appConfig, "appConfig");
        String analyzerName = appConfig.getIndexing() != null && appConfig.getIndexing().getAnalyzer() != null
                ? appConfig.getIndexing().getAnalyzer()
                : AnalyzerConfig.STANDARD;
        List<FieldConfig> fieldConfigs = appConfig.getFieldConfigs() == null ? List.of() : appConfig.getFieldConfigs();
        List<FieldSchema> fieldSchemas = new ArrayList<>();
        for (FieldConfig fieldConfig : fieldConfigs) {
            if (fieldConfig != null && fieldConfig.getName() != null && !fieldConfig.getName().isBlank()) {
                fieldSchemas.add(FieldSchema.from(fieldConfig));
            }
        }
        EmbeddingModelIdentity resolved = embedding;
        if (resolved == null) {
            resolved = embeddingFrom(appConfig, 0);
        }
        return current(AnalyzerConfig.of(analyzerName), fieldSchemas, resolved);
    }

    public static EmbeddingModelIdentity embeddingFrom(AppConfig appConfig, int fallbackDimension) {
        if (appConfig == null
                || appConfig.getMl() == null
                || appConfig.getMl().getModels() == null
                || appConfig.getMl().getModels().getTextEmbedding() == null) {
            return EmbeddingModelIdentity.unspecified(fallbackDimension);
        }
        AppConfig.TextEmbeddingConfig embeddingConfig = appConfig.getMl().getModels().getTextEmbedding();
        int dimension = embeddingConfig.getDimension() > 0 ? embeddingConfig.getDimension() : fallbackDimension;
        return EmbeddingModelIdentity.of(embeddingConfig.getUrl(), embeddingConfig.getEngine(), dimension);
    }

    private static List<FieldSchema> sorted(List<FieldSchema> fields) {
        List<FieldSchema> copy = new ArrayList<>(fields);
        copy.sort(Comparator.comparing(FieldSchema::name));
        return copy;
    }
}
