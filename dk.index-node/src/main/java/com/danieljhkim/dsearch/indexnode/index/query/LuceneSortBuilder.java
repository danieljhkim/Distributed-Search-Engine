package com.danieljhkim.dsearch.indexnode.index.query;

import com.danieljhkim.dsearch.common.config.AppConfig.FieldConfig;
import com.danieljhkim.dsearch.common.enums.FieldType;
import com.danieljhkim.dsearch.common.pagination.SortSpec;
import com.danieljhkim.dsearch.common.pagination.SortValues;
import com.danieljhkim.dsearch.proto.common.SortValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.util.BytesRef;

/**
 * Translates a wire sort spec into Lucene's ordering primitives and back.
 *
 * <p>Three conversions have to stay mutually consistent, or a cursor traversal will skip or
 * repeat documents: the {@link Sort} the shard collects under, the {@link FieldDoc} that marks
 * where to resume, and the {@link SortValue} tuple reported back for the next cursor.
 *
 * <h2>Missing values</h2>
 *
 * A document without a value for a sort field orders last, in both directions. Lucene expresses
 * that with a per-field sentinel that participates in the normal comparison, so the sentinel has
 * to be picked per direction: the largest value when ascending, the smallest when descending,
 * since a reversed comparator sends the smallest to the end.
 *
 * <p>One consequence is worth naming: a document whose real value equals the sentinel is reported
 * with {@code missing=true} in its sort tuple. Ordering stays correct — a real extreme value and a
 * missing value occupy the same position — and the tuple still round-trips through
 * {@link #toSearchAfter} to the same sentinel, so traversal is unaffected. Only the reported tuple
 * is imprecise, and only for a value at the exact numeric extreme.
 */
public final class LuceneSortBuilder {

    private LuceneSortBuilder() {}

    /** Lucene {@code Sort} for the effective spec. Never null for a sorted spec. */
    public static Sort toLuceneSort(SortSpec spec, String docIdField, Map<String, FieldConfig> fieldConfigs) {
        List<SortField> sortFields = new ArrayList<>(spec.size());
        for (SortSpec.SortComponent component : spec.components()) {
            sortFields.add(toLuceneSortField(component, docIdField, fieldConfigs));
        }
        return new Sort(sortFields.toArray(new SortField[0]));
    }

    private static SortField toLuceneSortField(
            SortSpec.SortComponent component, String docIdField, Map<String, FieldConfig> fieldConfigs) {
        if (component.isScore()) {
            // Type.SCORE already orders high-to-low, so reverse selects *ascending* score here.
            return new SortField(null, SortField.Type.SCORE, !component.descending());
        }
        String field = component.isDocId() ? docIdField : component.field();
        SortField.Type type = component.isDocId() ? SortField.Type.STRING : luceneType(component.field(), fieldConfigs);
        SortField sortField = new SortField(field, type, component.descending());
        sortField.setMissingValue(missingSentinel(type, component.descending()));
        return sortField;
    }

    private static SortField.Type luceneType(String field, Map<String, FieldConfig> fieldConfigs) {
        FieldConfig config = fieldConfigs == null ? null : fieldConfigs.get(field);
        if (config == null) {
            throw new IllegalArgumentException("Unknown sort field '" + field + "'");
        }
        if (!config.isSortable()) {
            throw new IllegalArgumentException(
                    "Field '" + field + "' is not sortable; mark it sortable in the index schema to order by it");
        }
        FieldType fieldType = config.getType() == null ? FieldType.STRING : config.getType();
        return switch (fieldType) {
            case INTEGER, LONG, DATE -> SortField.Type.LONG;
            case DOUBLE -> SortField.Type.DOUBLE;
            case STRING -> SortField.Type.STRING;
        };
    }

    private static Object missingSentinel(SortField.Type type, boolean descending) {
        return switch (type) {
            case LONG -> descending ? Long.MIN_VALUE : Long.MAX_VALUE;
            case DOUBLE -> descending ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            case STRING -> descending ? SortField.STRING_FIRST : SortField.STRING_LAST;
            default -> null;
        };
    }

    /**
     * Resume marker for {@code IndexSearcher.searchAfter}.
     *
     * <p>{@code doc} is {@code maxDoc - 1}, and the reason is subtle. Lucene's paging collector
     * skips a document when its sort tuple ties the marker <em>and</em> its ordinal is at most
     * {@code after.doc - docBase}. Because the effective spec ends in the unique document id,
     * exactly one document can tie the marker: the boundary document itself, the last hit of the
     * previous page. That one must always be skipped. {@code maxDoc - 1} makes the ordinal test
     * true in every segment, so the boundary document is excluded wherever it lives, while
     * remaining inside the bounds check Lucene applies to the marker.
     *
     * <p>A smaller value — 0, say — leaves the boundary document eligible whenever its ordinal is
     * higher, which repeats one hit per page.
     *
     * @param maxDoc {@code maxDoc()} of the reader the resumed search will run against
     */
    public static FieldDoc toSearchAfter(
            SortSpec spec,
            List<SortValue> values,
            String docIdField,
            Map<String, FieldConfig> fieldConfigs,
            int maxDoc) {
        if (values == null || values.size() != spec.size()) {
            throw new IllegalArgumentException("search_after must have one value per sort field");
        }
        Object[] fields = new Object[spec.size()];
        float score = Float.NaN;
        for (int i = 0; i < spec.size(); i++) {
            SortSpec.SortComponent component = spec.components().get(i);
            SortValue value = values.get(i);
            SortField luceneField = toLuceneSortField(component, docIdField, fieldConfigs);
            fields[i] = toLuceneValue(luceneField.getType(), value, luceneField.getMissingValue());
            if (luceneField.getType() == SortField.Type.SCORE && fields[i] instanceof Float floatValue) {
                score = floatValue;
            }
        }
        return new FieldDoc(Math.max(0, maxDoc - 1), score, fields);
    }

    private static Object toLuceneValue(SortField.Type type, SortValue value, Object missingSentinel) {
        if (SortValues.isMissing(value)) {
            return missingSentinel;
        }
        return switch (type) {
            case LONG ->
                value.getValueCase() == SortValue.ValueCase.LONG_VALUE
                        ? value.getLongValue()
                        : (long) value.getDoubleValue();
            case DOUBLE ->
                value.getValueCase() == SortValue.ValueCase.DOUBLE_VALUE
                        ? value.getDoubleValue()
                        : (double) value.getLongValue();
            case SCORE ->
                value.getValueCase() == SortValue.ValueCase.FLOAT_VALUE
                        ? value.getFloatValue()
                        : (float) value.getDoubleValue();
            case STRING -> new BytesRef(value.getStringValue());
            default -> throw new IllegalArgumentException("Unsupported sort type " + type);
        };
    }

    /** Sort tuple to report for a collected hit, matched positionally to the spec. */
    public static List<SortValue> toSortValues(
            SortSpec spec, FieldDoc fieldDoc, String docIdField, Map<String, FieldConfig> fieldConfigs) {
        List<SortValue> values = new ArrayList<>(spec.size());
        Object[] fields = fieldDoc.fields;
        for (int i = 0; i < spec.size(); i++) {
            SortSpec.SortComponent component = spec.components().get(i);
            SortField luceneField = toLuceneSortField(component, docIdField, fieldConfigs);
            Object raw = fields != null && i < fields.length ? fields[i] : null;
            values.add(toSortValue(luceneField, raw));
        }
        return values;
    }

    private static SortValue toSortValue(SortField luceneField, Object raw) {
        // Lucene reports a missing string as a null BytesRef, and a missing number as the sentinel
        // we installed above. Both mean "this document has no value for this field".
        if (raw == null) {
            return SortValues.missing();
        }
        Object missingSentinel = luceneField.getMissingValue();
        return switch (luceneField.getType()) {
            case LONG -> {
                long value = ((Number) raw).longValue();
                yield missingSentinel instanceof Long sentinel && sentinel == value
                        ? SortValues.missing()
                        : SortValues.of(value);
            }
            case DOUBLE -> {
                double value = ((Number) raw).doubleValue();
                yield missingSentinel instanceof Double sentinel && sentinel == value
                        ? SortValues.missing()
                        : SortValues.of(value);
            }
            case SCORE -> SortValues.of(((Number) raw).floatValue());
            case STRING -> SortValues.of(((BytesRef) raw).utf8ToString());
            default -> SortValues.missing();
        };
    }
}
