package com.danieljhkim.dsearch.indexnode.index.query;

import com.danieljhkim.dsearch.common.config.AppConfig.FieldConfig;
import com.danieljhkim.dsearch.common.enums.FieldType;
import com.danieljhkim.dsearch.proto.common.Filter;
import com.danieljhkim.dsearch.proto.common.FilterOperator;
import java.util.List;
import java.util.Map;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;

/**
 * Builds Lucene queries from filter specifications.
 * Converts Filter proto messages to appropriate Lucene queries (TermQuery,
 * RangeQuery, BooleanQuery).
 */
public class FilterQueryBuilder {

    private final Map<String, FieldConfig> fieldConfigMap;

    public FilterQueryBuilder(Map<String, FieldConfig> fieldConfigMap) {
        this.fieldConfigMap = fieldConfigMap != null ? fieldConfigMap : Map.of();
    }

    /**
     * Builds a Lucene query from a list of filters.
     * Multiple filters are combined with AND logic.
     *
     * @param filters
     *            the list of filters
     * @return the combined Lucene query, or MatchAllDocsQuery if filters is empty
     */
    public Query buildQuery(List<Filter> filters) {
        if (filters == null || filters.isEmpty()) {
            return new MatchAllDocsQuery();
        }

        if (filters.size() == 1) {
            return buildSingleFilter(filters.getFirst());
        }

        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (Filter filter : filters) {
            Query filterQuery = buildSingleFilter(filter);
            builder.add(filterQuery, BooleanClause.Occur.MUST);
        }
        return builder.build();
    }

    /**
     * Builds a Lucene query from a single filter.
     */
    private Query buildSingleFilter(Filter filter) {
        String field = filter.getField();
        FilterOperator operator = filter.getOperator();
        List<String> values = filter.getValuesList();

        FieldConfig config = fieldConfigMap.get(field);
        FieldType fieldType = (config != null) ? config.getType() : FieldType.STRING;

        return switch (operator) {
            case EQ -> buildEqualsQuery(field, values, fieldType);
            case NE -> buildNotEqualsQuery(field, values, fieldType);
            case GT -> buildRangeQuery(field, values, fieldType, false, true);
            case GTE -> buildRangeQuery(field, values, fieldType, true, true);
            case LT -> buildRangeQuery(field, values, fieldType, false, false);
            case LTE -> buildRangeQuery(field, values, fieldType, true, false);
            case IN -> buildInQuery(field, values, fieldType);
            case NOT_IN -> buildNotInQuery(field, values, fieldType);
            default -> new MatchAllDocsQuery();
        };
    }

    private Query buildEqualsQuery(String field, List<String> values, FieldType fieldType) {
        if (values.isEmpty()) {
            return new MatchAllDocsQuery();
        }
        String value = values.getFirst();
        return buildTermQuery(field, value, fieldType);
    }

    private Query buildNotEqualsQuery(String field, List<String> values, FieldType fieldType) {
        if (values.isEmpty()) {
            return new MatchAllDocsQuery();
        }
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST);
        builder.add(buildTermQuery(field, values.getFirst(), fieldType), BooleanClause.Occur.MUST_NOT);
        return builder.build();
    }

    private Query buildInQuery(String field, List<String> values, FieldType fieldType) {
        if (values.isEmpty()) {
            return new MatchAllDocsQuery();
        }
        if (values.size() == 1) {
            return buildTermQuery(field, values.getFirst(), fieldType);
        }
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (String value : values) {
            builder.add(buildTermQuery(field, value, fieldType), BooleanClause.Occur.SHOULD);
        }
        builder.setMinimumNumberShouldMatch(1);
        return builder.build();
    }

    private Query buildNotInQuery(String field, List<String> values, FieldType fieldType) {
        if (values.isEmpty()) {
            return new MatchAllDocsQuery();
        }
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST);
        for (String value : values) {
            builder.add(buildTermQuery(field, value, fieldType), BooleanClause.Occur.MUST_NOT);
        }
        return builder.build();
    }

    private Query buildTermQuery(String field, String value, FieldType fieldType) {
        return switch (fieldType) {
            case INTEGER -> IntPoint.newExactQuery(field, Integer.parseInt(value));
            case LONG, DATE -> LongPoint.newExactQuery(field, Long.parseLong(value));
            case DOUBLE -> DoublePoint.newExactQuery(field, Double.parseDouble(value));
            default -> new TermQuery(new Term(field, value));
        };
    }

    private Query buildRangeQuery(
            String field, List<String> values, FieldType fieldType, boolean inclusive, boolean greaterThan) {
        if (values.isEmpty()) {
            return new MatchAllDocsQuery();
        }
        String value = values.getFirst();

        return switch (fieldType) {
            case INTEGER -> buildIntRangeQuery(field, value, inclusive, greaterThan);
            case LONG, DATE -> buildLongRangeQuery(field, value, inclusive, greaterThan);
            case DOUBLE -> buildDoubleRangeQuery(field, value, inclusive, greaterThan);
            default -> new MatchAllDocsQuery(); // String range queries not supported
        };
    }

    private Query buildIntRangeQuery(String field, String value, boolean inclusive, boolean greaterThan) {
        int intValue = Integer.parseInt(value);
        if (greaterThan) {
            int lower = inclusive ? intValue : Math.addExact(intValue, 1);
            return IntPoint.newRangeQuery(field, lower, Integer.MAX_VALUE);
        } else {
            int upper = inclusive ? intValue : Math.addExact(intValue, -1);
            return IntPoint.newRangeQuery(field, Integer.MIN_VALUE, upper);
        }
    }

    private Query buildLongRangeQuery(String field, String value, boolean inclusive, boolean greaterThan) {
        long longValue = Long.parseLong(value);
        if (greaterThan) {
            long lower = inclusive ? longValue : Math.addExact(longValue, 1L);
            return LongPoint.newRangeQuery(field, lower, Long.MAX_VALUE);
        } else {
            long upper = inclusive ? longValue : Math.addExact(longValue, -1L);
            return LongPoint.newRangeQuery(field, Long.MIN_VALUE, upper);
        }
    }

    private Query buildDoubleRangeQuery(String field, String value, boolean inclusive, boolean greaterThan) {
        double doubleValue = Double.parseDouble(value);
        if (greaterThan) {
            double lower = inclusive ? doubleValue : Math.nextUp(doubleValue);
            return DoublePoint.newRangeQuery(field, lower, Double.MAX_VALUE);
        } else {
            double upper = inclusive ? doubleValue : Math.nextDown(doubleValue);
            return DoublePoint.newRangeQuery(field, -Double.MAX_VALUE, upper);
        }
    }
}
