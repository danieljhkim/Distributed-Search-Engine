package com.danieljhkim.dsearch.indexnode.index.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.common.config.AppConfig.FieldConfig;
import com.danieljhkim.dsearch.common.enums.FieldType;
import com.danieljhkim.dsearch.proto.common.Filter;
import com.danieljhkim.dsearch.proto.common.FilterOperator;
import java.util.List;
import java.util.Map;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.PointRangeQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.Test;

class FilterQueryBuilderTest {

    private final FilterQueryBuilder builder = new FilterQueryBuilder(fieldConfigs());

    @Test
    void nullAndEmptyFilterListsReturnMatchAll() {
        assertInstanceOf(MatchAllDocsQuery.class, builder.buildQuery(null));
        assertInstanceOf(MatchAllDocsQuery.class, builder.buildQuery(List.of()));
    }

    @Test
    void stringEqualsIsTermQuery() {
        Query query = builder.buildQuery(List.of(filter("status", FilterOperator.EQ, "active")));

        TermQuery termQuery = assertInstanceOf(TermQuery.class, query);
        assertEquals("status", termQuery.getTerm().field());
        assertEquals("active", termQuery.getTerm().text());
    }

    @Test
    void stringNotEqualsIsMustNotTermQuery() {
        Query query = builder.buildQuery(List.of(filter("status", FilterOperator.NE, "archived")));

        BooleanQuery booleanQuery = assertInstanceOf(BooleanQuery.class, query);
        assertEquals(2, booleanQuery.clauses().size());
        assertInstanceOf(MatchAllDocsQuery.class, clauseQuery(booleanQuery, 0));
        assertEquals(BooleanClause.Occur.MUST, booleanQuery.clauses().get(0).occur());

        TermQuery excluded = assertInstanceOf(TermQuery.class, clauseQuery(booleanQuery, 1));
        assertEquals(BooleanClause.Occur.MUST_NOT, booleanQuery.clauses().get(1).occur());
        assertEquals("status", excluded.getTerm().field());
        assertEquals("archived", excluded.getTerm().text());
    }

    @Test
    void stringInBuildsShouldBooleanQuery() {
        Query query = builder.buildQuery(List.of(filter("status", FilterOperator.IN, "active", "draft")));

        BooleanQuery booleanQuery = assertInstanceOf(BooleanQuery.class, query);
        assertEquals(1, booleanQuery.getMinimumNumberShouldMatch());
        assertEquals(2, booleanQuery.clauses().size());
        assertTrue(booleanQuery.clauses().stream().allMatch(clause -> clause.occur() == BooleanClause.Occur.SHOULD));

        TermQuery first = assertInstanceOf(TermQuery.class, clauseQuery(booleanQuery, 0));
        TermQuery second = assertInstanceOf(TermQuery.class, clauseQuery(booleanQuery, 1));
        assertEquals("active", first.getTerm().text());
        assertEquals("draft", second.getTerm().text());
        assertEquals("status", first.getTerm().field());
        assertEquals("status", second.getTerm().field());
    }

    @Test
    void stringNotInBuildsMustNotTermQueries() {
        Query query = builder.buildQuery(List.of(filter("status", FilterOperator.NOT_IN, "spam", "deleted")));

        BooleanQuery booleanQuery = assertInstanceOf(BooleanQuery.class, query);
        assertEquals(3, booleanQuery.clauses().size());
        assertInstanceOf(MatchAllDocsQuery.class, clauseQuery(booleanQuery, 0));
        assertEquals(BooleanClause.Occur.MUST, booleanQuery.clauses().get(0).occur());

        TermQuery firstExcluded = assertInstanceOf(TermQuery.class, clauseQuery(booleanQuery, 1));
        TermQuery secondExcluded = assertInstanceOf(TermQuery.class, clauseQuery(booleanQuery, 2));
        assertEquals(BooleanClause.Occur.MUST_NOT, booleanQuery.clauses().get(1).occur());
        assertEquals(BooleanClause.Occur.MUST_NOT, booleanQuery.clauses().get(2).occur());
        assertEquals("spam", firstExcluded.getTerm().text());
        assertEquals("deleted", secondExcluded.getTerm().text());
    }

    @Test
    void integerRangeOperatorsSetInclusiveAndExclusiveBounds() {
        assertIntRange(FilterOperator.GT, 10, 11, Integer.MAX_VALUE);
        assertIntRange(FilterOperator.GTE, 10, 10, Integer.MAX_VALUE);
        assertIntRange(FilterOperator.LT, 10, Integer.MIN_VALUE, 9);
        assertIntRange(FilterOperator.LTE, 10, Integer.MIN_VALUE, 10);
    }

    @Test
    void longDateAndDoubleQueriesDispatchByFieldType() {
        PointRangeQuery dateQuery = assertInstanceOf(
                PointRangeQuery.class,
                builder.buildQuery(List.of(filter("createdAt", FilterOperator.GTE, "1700000000000"))));
        assertEquals("createdAt", dateQuery.getField());
        assertEquals(1_700_000_000_000L, LongPoint.decodeDimension(dateQuery.getLowerPoint(), 0));
        assertEquals(Long.MAX_VALUE, LongPoint.decodeDimension(dateQuery.getUpperPoint(), 0));

        PointRangeQuery doubleQuery = assertInstanceOf(
                PointRangeQuery.class, builder.buildQuery(List.of(filter("score", FilterOperator.EQ, "3.5"))));
        assertEquals("score", doubleQuery.getField());
        assertEquals(3.5d, DoublePoint.decodeDimension(doubleQuery.getLowerPoint(), 0));
        assertEquals(3.5d, DoublePoint.decodeDimension(doubleQuery.getUpperPoint(), 0));
    }

    @Test
    void multipleFiltersAreCombinedWithMust() {
        Query query = builder.buildQuery(
                List.of(filter("status", FilterOperator.EQ, "active"), filter("year", FilterOperator.GTE, "2021")));

        BooleanQuery booleanQuery = assertInstanceOf(BooleanQuery.class, query);
        assertEquals(2, booleanQuery.clauses().size());
        assertTrue(booleanQuery.clauses().stream().allMatch(clause -> clause.occur() == BooleanClause.Occur.MUST));

        TermQuery statusQuery = assertInstanceOf(TermQuery.class, clauseQuery(booleanQuery, 0));
        assertEquals("status", statusQuery.getTerm().field());
        assertEquals("active", statusQuery.getTerm().text());

        PointRangeQuery yearQuery = assertInstanceOf(PointRangeQuery.class, clauseQuery(booleanQuery, 1));
        assertEquals("year", yearQuery.getField());
        assertEquals(2021, IntPoint.decodeDimension(yearQuery.getLowerPoint(), 0));
        assertEquals(Integer.MAX_VALUE, IntPoint.decodeDimension(yearQuery.getUpperPoint(), 0));
    }

    @Test
    void unknownOperatorAndStringRangeOperatorsReturnMatchAll() {
        assertInstanceOf(
                MatchAllDocsQuery.class,
                builder.buildQuery(List.of(filter("status", FilterOperator.FILTER_OP_UNSPECIFIED, "active"))));
        assertInstanceOf(
                MatchAllDocsQuery.class, builder.buildQuery(List.of(filter("status", FilterOperator.GT, "m"))));
        assertInstanceOf(
                MatchAllDocsQuery.class, builder.buildQuery(List.of(filter("status", FilterOperator.GTE, "m"))));
        assertInstanceOf(
                MatchAllDocsQuery.class, builder.buildQuery(List.of(filter("status", FilterOperator.LT, "m"))));
        assertInstanceOf(
                MatchAllDocsQuery.class, builder.buildQuery(List.of(filter("status", FilterOperator.LTE, "m"))));
    }

    private void assertIntRange(FilterOperator operator, int value, int expectedLower, int expectedUpper) {
        PointRangeQuery range = assertInstanceOf(
                PointRangeQuery.class, builder.buildQuery(List.of(filter("year", operator, Integer.toString(value)))));
        assertEquals("year", range.getField());
        assertEquals(expectedLower, IntPoint.decodeDimension(range.getLowerPoint(), 0));
        assertEquals(expectedUpper, IntPoint.decodeDimension(range.getUpperPoint(), 0));
    }

    private static Query clauseQuery(BooleanQuery query, int index) {
        return query.clauses().get(index).query();
    }

    private static Filter filter(String field, FilterOperator operator, String... values) {
        return Filter.newBuilder()
                .setField(field)
                .setOperator(operator)
                .addAllValues(List.of(values))
                .build();
    }

    private static Map<String, FieldConfig> fieldConfigs() {
        return Map.of(
                "status", fieldConfig("status", FieldType.STRING),
                "year", fieldConfig("year", FieldType.INTEGER),
                "createdAt", fieldConfig("createdAt", FieldType.DATE),
                "score", fieldConfig("score", FieldType.DOUBLE));
    }

    private static FieldConfig fieldConfig(String name, FieldType type) {
        FieldConfig config = new FieldConfig();
        config.setName(name);
        config.setType(type);
        config.setFilterable(true);
        return config;
    }
}
