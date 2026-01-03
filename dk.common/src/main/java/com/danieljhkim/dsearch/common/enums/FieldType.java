package com.danieljhkim.dsearch.common.enums;

/**
 * Enum representing the data types for document fields.
 * Used to determine how fields are indexed and queried in Lucene.
 */
public enum FieldType {
    /**
     * String field - indexed as exact match (StringField) or full-text (TextField)
     */
    STRING,

    /**
     * Integer field - indexed using IntPoint for range queries
     */
    INTEGER,

    /**
     * Long field - indexed using LongPoint for range queries
     */
    LONG,

    /**
     * Double field - indexed using DoublePoint for range queries
     */
    DOUBLE,

    /**
     * Date field - stored as epoch milliseconds (long) for range queries
     */
    DATE
}
