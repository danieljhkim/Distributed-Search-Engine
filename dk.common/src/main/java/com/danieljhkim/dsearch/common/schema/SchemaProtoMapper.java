package com.danieljhkim.dsearch.common.schema;

import com.danieljhkim.dsearch.common.enums.FieldType;
import com.danieljhkim.dsearch.proto.index.IndexSchemaField;
import java.util.ArrayList;
import java.util.List;

public final class SchemaProtoMapper {

    private SchemaProtoMapper() {}

    public static com.danieljhkim.dsearch.proto.index.IndexSchema toProto(IndexSchema schema) {
        if (schema == null) {
            return com.danieljhkim.dsearch.proto.index.IndexSchema.getDefaultInstance();
        }
        com.danieljhkim.dsearch.proto.index.IndexSchema.Builder builder =
                com.danieljhkim.dsearch.proto.index.IndexSchema.newBuilder()
                        .setCompatibilityVersion(schema.compatibilityVersion())
                        .setAnalyzer(com.danieljhkim.dsearch.proto.index.AnalyzerConfig.newBuilder()
                                .setName(schema.analyzer().name())
                                .build())
                        .setEmbedding(com.danieljhkim.dsearch.proto.index.EmbeddingModelIdentity.newBuilder()
                                .setModelId(schema.embedding().modelId())
                                .setEngine(schema.embedding().engine())
                                .setDigest(schema.embedding().digest())
                                .setDimension(schema.embedding().dimension())
                                .build());
        for (FieldSchema field : schema.fields()) {
            builder.addFields(IndexSchemaField.newBuilder()
                    .setName(field.name())
                    .setType(field.type().name())
                    .setFilterable(field.filterable())
                    .setSortable(field.sortable())
                    .setFacetable(field.facetable())
                    .setHighlightable(field.highlightable())
                    .setAnalyzer(field.analyzer())
                    .build());
        }
        return builder.build();
    }

    public static IndexSchema fromProto(com.danieljhkim.dsearch.proto.index.IndexSchema proto) {
        if (proto == null || proto.equals(com.danieljhkim.dsearch.proto.index.IndexSchema.getDefaultInstance())) {
            return null;
        }
        List<FieldSchema> fields = new ArrayList<>();
        for (IndexSchemaField field : proto.getFieldsList()) {
            FieldType type;
            try {
                type = FieldType.valueOf(field.getType());
            } catch (IllegalArgumentException e) {
                type = FieldType.STRING;
            }
            fields.add(new FieldSchema(
                    field.getName(),
                    type,
                    field.getFilterable(),
                    field.getSortable(),
                    field.getFacetable(),
                    field.getHighlightable(),
                    field.getAnalyzer()));
        }
        int compatibilityVersion =
                proto.getCompatibilityVersion() > 0 ? proto.getCompatibilityVersion() : IndexSchema.CURRENT_COMPATIBILITY_VERSION;
        AnalyzerConfig analyzer = AnalyzerConfig.of(proto.getAnalyzer().getName());
        com.danieljhkim.dsearch.proto.index.EmbeddingModelIdentity embedding = proto.getEmbedding();
        EmbeddingModelIdentity identity = EmbeddingModelIdentity.of(
                embedding.getModelId(), embedding.getEngine(), embedding.getDimension());
        if (!embedding.getDigest().isBlank()) {
            identity = new EmbeddingModelIdentity(
                    identity.modelId(), identity.engine(), embedding.getDigest(), identity.dimension());
        }
        return new IndexSchema(compatibilityVersion, analyzer, fields, identity);
    }
}
