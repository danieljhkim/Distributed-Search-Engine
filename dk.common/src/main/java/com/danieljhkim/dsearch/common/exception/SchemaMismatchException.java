package com.danieljhkim.dsearch.common.exception;

/**
 * Raised when persisted index metadata cannot be interpreted by the running process.
 *
 * <p>{@link #getProperty()} names the mismatched field or model property, for example
 * {@code fields.title.analyzer} or {@code embedding.dimension}.
 */
public class SchemaMismatchException extends InvalidIndexStateException {

    private final String property;

    public SchemaMismatchException(String property, String message) {
        super(message);
        this.property = property;
    }

    public String getProperty() {
        return property;
    }

    public static SchemaMismatchException of(String property, String persisted, String runtime) {
        return new SchemaMismatchException(
                property,
                "Incompatible index metadata: " + property + " does not match (persisted="
                        + persisted + ", runtime=" + runtime + ")");
    }
}
