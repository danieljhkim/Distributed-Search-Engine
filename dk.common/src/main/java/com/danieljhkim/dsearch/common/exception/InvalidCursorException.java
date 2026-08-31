package com.danieljhkim.dsearch.common.exception;

/**
 * Raised when a pagination cursor cannot be used for the request that presented it.
 *
 * <p>A cursor is refused rather than repaired: resuming a traversal whose query, filters,
 * sort, page size, schema, or index generation has changed would silently return a result
 * set that is neither the old one nor the new one. {@link #getReason()} names the specific
 * incompatibility so a caller can distinguish "restart the traversal" from "fix the request".
 *
 * <p>Extends {@link IllegalArgumentException} so existing gRPC and HTTP layers already map it
 * to {@code INVALID_ARGUMENT} / {@code 400 Bad Request}.
 */
public class InvalidCursorException extends IllegalArgumentException {

    /** Why a cursor was refused. Stable enough to branch on. */
    public enum Reason {
        /** Not a cursor this build can parse at all. */
        MALFORMED,
        /** Parsed, but the signature does not match: the payload was altered in transit. */
        TAMPERED,
        /** A cursor format this build does not implement. */
        UNSUPPORTED_VERSION,
        /** The query, filters, sort, page size, or schema changed since the cursor was issued. */
        REQUEST_CHANGED,
        /** The alias now serves a different index generation than the cursor was issued against. */
        INDEX_CHANGED,
        /** Cursor pagination is not available for this request shape. */
        UNSUPPORTED_REQUEST
    }

    private final Reason reason;

    public InvalidCursorException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
