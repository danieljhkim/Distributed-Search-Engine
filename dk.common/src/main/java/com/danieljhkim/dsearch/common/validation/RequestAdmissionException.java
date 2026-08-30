package com.danieljhkim.dsearch.common.validation;

/** Signals bounded admission-control overload without allowing work to queue indefinitely. */
public class RequestAdmissionException extends RuntimeException {

    private final int retryAfterMillis;

    public RequestAdmissionException(String resource, int retryAfterMillis) {
        super(resource + " capacity exhausted; retry after " + Math.max(1, retryAfterMillis) + " ms");
        this.retryAfterMillis = Math.max(1, retryAfterMillis);
    }

    public int getRetryAfterMillis() {
        return retryAfterMillis;
    }
}
