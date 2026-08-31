package com.danieljhkim.dsearch.common.pagination;

import com.danieljhkim.dsearch.common.config.AppConfig.FieldConfig;
import com.danieljhkim.dsearch.common.schema.FieldSchema;
import com.danieljhkim.dsearch.common.schema.IndexSchema;
import com.danieljhkim.dsearch.proto.common.SortOrder;
import com.danieljhkim.dsearch.proto.common.SortValue;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A normalized, total ordering over search results.
 *
 * <p>A spec is "effective": the unique document-id tie-breaker {@code _id ASC} is already
 * appended, so no two distinct documents ever compare equal. That is what makes the ordering
 * stable enough to page through with {@code search_after} — every node can independently
 * answer "the hits strictly after this tuple" and the answers merge without duplicates or gaps.
 *
 * <p>This type is deliberately free of Lucene: the query node needs to compare sort tuples it
 * receives over the wire, and only the index node needs to turn a spec into a Lucene {@code Sort}.
 */
public record SortSpec(List<SortComponent> components) {

    /** Pseudo-field selecting relevance score order. */
    public static final String SCORE_FIELD = "_score";

    /** Pseudo-field selecting document id order; always the final tie-breaker. */
    public static final String DOC_ID_FIELD = "_id";

    private static final SortSpec UNSORTED = new SortSpec(List.of());

    /** Default ordering used when a cursor traversal is requested without an explicit sort. */
    private static final List<SortField> RELEVANCE_DEFAULT =
            List.of(new SortField(SCORE_FIELD, true), new SortField(DOC_ID_FIELD, false));

    public SortSpec {
        components = List.copyOf(components == null ? List.of() : components);
    }

    /** One ordering component. {@code descending} is the requested direction, before tie-breaking. */
    public record SortComponent(String field, boolean descending) {
        public SortComponent {
            Objects.requireNonNull(field, "field");
        }

        public boolean isScore() {
            return SCORE_FIELD.equals(field);
        }

        public boolean isDocId() {
            return DOC_ID_FIELD.equals(field);
        }
    }

    /** Direction-carrying view of a requested field, used only while normalizing. */
    private record SortField(String field, boolean descending) {}

    /** The spec meaning "no explicit ordering": relevance order, no sort values, no cursor. */
    public static SortSpec unsorted() {
        return UNSORTED;
    }

    public boolean isUnsorted() {
        return components.isEmpty();
    }

    public int size() {
        return components.size();
    }

    /**
     * Normalizes a requested ordering into an effective one.
     *
     * <p>Repeated fields keep their first occurrence, an explicit {@code _id} component is
     * honoured in place, and otherwise {@code _id ASC} is appended. An empty request stays
     * unsorted unless {@code forCursor} is set, in which case it becomes relevance order with
     * the same tie-breaker so the traversal is still resumable.
     */
    public static SortSpec effective(
            List<com.danieljhkim.dsearch.proto.common.SortField> requested, boolean forCursor) {
        List<SortField> normalized = new ArrayList<>();
        if (requested != null) {
            for (com.danieljhkim.dsearch.proto.common.SortField field : requested) {
                if (field == null) {
                    continue;
                }
                String name = field.getField() == null ? "" : field.getField().trim();
                if (name.isEmpty()) {
                    throw new IllegalArgumentException("sort field name must not be blank");
                }
                if (field.getOrder() == SortOrder.UNRECOGNIZED) {
                    throw new IllegalArgumentException("Unrecognized sort order for field '" + name + "'");
                }
                normalized.add(new SortField(name, field.getOrder() == SortOrder.SORT_ORDER_DESC));
            }
        }
        if (normalized.isEmpty()) {
            if (!forCursor) {
                return UNSORTED;
            }
            normalized = new ArrayList<>(RELEVANCE_DEFAULT);
        }

        List<SortComponent> components = new ArrayList<>(normalized.size() + 1);
        Set<String> seen = new LinkedHashSet<>();
        boolean tieBroken = false;
        for (SortField field : normalized) {
            if (!seen.add(field.field())) {
                // A field cannot order the same result set twice; the first occurrence wins.
                continue;
            }
            components.add(new SortComponent(field.field(), field.descending()));
            if (DOC_ID_FIELD.equals(field.field())) {
                // _id is unique, so nothing after it could ever be consulted.
                tieBroken = true;
                break;
            }
        }
        if (!tieBroken) {
            components.add(new SortComponent(DOC_ID_FIELD, false));
        }
        return new SortSpec(components);
    }

    /**
     * Rejects components that the index cannot order by.
     *
     * <p>A null schema means the caller has no authoritative view — the index node validates
     * against its own field configuration, so this is a fast pre-check, not the only one.
     */
    public void validateAgainst(IndexSchema schema) {
        if (schema == null) {
            return;
        }
        for (SortComponent component : components) {
            if (component.isScore() || component.isDocId()) {
                continue;
            }
            FieldSchema field = null;
            for (FieldSchema candidate : schema.fields()) {
                if (candidate.name().equals(component.field())) {
                    field = candidate;
                    break;
                }
            }
            if (field == null) {
                throw new IllegalArgumentException("Unknown sort field '" + component.field() + "'");
            }
            if (!field.sortable()) {
                throw new IllegalArgumentException("Field '" + component.field()
                        + "' is not sortable; mark it sortable in the index schema to order by it");
            }
        }
    }

    /** Same check as {@link #validateAgainst(IndexSchema)} against a runtime field configuration. */
    public void validateAgainst(Map<String, FieldConfig> fieldConfigs) {
        if (fieldConfigs == null) {
            return;
        }
        for (SortComponent component : components) {
            if (component.isScore() || component.isDocId()) {
                continue;
            }
            FieldConfig config = fieldConfigs.get(component.field());
            if (config == null) {
                throw new IllegalArgumentException("Unknown sort field '" + component.field() + "'");
            }
            if (!config.isSortable()) {
                throw new IllegalArgumentException("Field '" + component.field()
                        + "' is not sortable; mark it sortable in the index schema to order by it");
            }
        }
    }

    /** Stable textual form, hashed into the cursor fingerprint. */
    public String canonicalForm() {
        StringBuilder builder = new StringBuilder();
        for (SortComponent component : components) {
            builder.append(component.field())
                    .append(component.descending() ? ":desc" : ":asc")
                    .append(';');
        }
        return builder.toString();
    }

    /** Back to the wire form, so the effective ordering travels to the index nodes verbatim. */
    public List<com.danieljhkim.dsearch.proto.common.SortField> toProto() {
        List<com.danieljhkim.dsearch.proto.common.SortField> protoFields = new ArrayList<>(components.size());
        for (SortComponent component : components) {
            protoFields.add(com.danieljhkim.dsearch.proto.common.SortField.newBuilder()
                    .setField(component.field())
                    .setOrder(component.descending() ? SortOrder.SORT_ORDER_DESC : SortOrder.SORT_ORDER_ASC)
                    .build());
        }
        return protoFields;
    }

    /**
     * Comparator over sort tuples produced under this spec.
     *
     * <p>Tuples are positional: element {@code i} belongs to component {@code i}. A tuple shorter
     * than the spec compares as missing from that point on, which keeps a malformed or truncated
     * response ordered last instead of throwing mid-merge.
     */
    public Comparator<List<SortValue>> tupleComparator() {
        return (left, right) -> {
            for (int i = 0; i < components.size(); i++) {
                SortValue leftValue = valueAt(left, i);
                SortValue rightValue = valueAt(right, i);
                int comparison = SortValues.compare(
                        leftValue, rightValue, components.get(i).descending());
                if (comparison != 0) {
                    return comparison;
                }
            }
            return 0;
        };
    }

    private static SortValue valueAt(List<SortValue> tuple, int index) {
        if (tuple == null || index >= tuple.size()) {
            return SortValues.missing();
        }
        SortValue value = tuple.get(index);
        return value == null ? SortValues.missing() : value;
    }
}
