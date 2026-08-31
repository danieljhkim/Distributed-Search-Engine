package com.danieljhkim.dsearch.common.pagination;

import com.danieljhkim.dsearch.common.schema.FieldSchema;
import com.danieljhkim.dsearch.common.schema.IndexSchema;
import com.danieljhkim.dsearch.proto.common.Filter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Digest of everything a cursor traversal assumes stays constant.
 *
 * <p>A {@code search_after} cursor is only meaningful against the exact result set that produced
 * it. Change the query, the filters, the ordering, the page size, or the field types underneath,
 * and resuming from a stored sort tuple lands somewhere arbitrary in a different result set. So
 * the cursor carries this fingerprint and the server recomputes it on every resume: a mismatch is
 * an explicit rejection rather than a plausible-looking wrong page.
 *
 * <p>Filter clauses and the values inside them are order-normalized, because a conjunction of
 * filters describes the same result set regardless of the order it arrived in — a client that
 * rebuilds its filter list between pages should still be able to resume.
 */
public final class RequestFingerprint {

    private static final char UNIT_SEPARATOR = '\u001F';

    private RequestFingerprint() {}

    public static byte[] of(
            String queryString,
            String partitionId,
            String searchType,
            String fusionStrategy,
            Collection<Filter> filters,
            SortSpec sort,
            int size,
            IndexSchema schema) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, "q", queryString);
        append(canonical, "partition", partitionId);
        append(canonical, "type", searchType);
        append(canonical, "fusion", fusionStrategy);
        append(canonical, "filters", canonicalFilters(filters));
        append(canonical, "sort", sort == null ? "" : sort.canonicalForm());
        append(canonical, "size", Integer.toString(size));
        append(canonical, "schema", canonicalSchema(schema));
        return sha256(canonical.toString());
    }

    private static void append(StringBuilder builder, String label, String value) {
        builder.append(label).append('=').append(value == null ? "" : value).append(UNIT_SEPARATOR);
    }

    private static String canonicalFilters(Collection<Filter> filters) {
        if (filters == null || filters.isEmpty()) {
            return "";
        }
        List<String> clauses = new ArrayList<>(filters.size());
        for (Filter filter : filters) {
            if (filter == null) {
                continue;
            }
            List<String> values = new ArrayList<>(filter.getValuesList());
            Collections.sort(values);
            clauses.add(filter.getField() + ':' + filter.getOperator().name() + ':' + String.join(",", values));
        }
        Collections.sort(clauses);
        return String.join("|", clauses);
    }

    /**
     * Field-level view of the schema. Only the properties that can change how a sort tuple is
     * produced or ordered participate; embedding identity is covered by the existing schema
     * compatibility check rather than duplicated here.
     */
    private static String canonicalSchema(IndexSchema schema) {
        if (schema == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(schema.compatibilityVersion())
                .append(':')
                .append(schema.analyzer().name())
                .append('|');
        for (FieldSchema field : schema.fields()) {
            builder.append(field.name())
                    .append(':')
                    .append(field.type())
                    .append(':')
                    .append(field.sortable() ? '1' : '0')
                    .append(field.filterable() ? '1' : '0')
                    .append(',');
        }
        return builder.toString();
    }

    private static byte[] sha256(String canonical) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every supported JRE", e);
        }
    }
}
