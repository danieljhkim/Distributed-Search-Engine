package com.danieljhkim.dsearch.common.validation;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.config.ConfigLoader;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RequestLimitsValidator {

    private static final Logger LOGGER = Logger.getLogger(RequestLimitsValidator.class.getName());
    private static AppConfig.RequestLimitsConfig limits;

    static {
        try {
            AppConfig appConfig = ConfigLoader.load();
            limits = appConfig != null ? appConfig.getRequestLimits() : null;
        } catch (IOException e) {
            // If config can't be loaded, skip validation to avoid breaking the service
            LOGGER.log(Level.SEVERE, "Failed to load application configuration", e);
        }
    }

    private RequestLimitsValidator() {
        // Utility class - prevent instantiation
    }

    /**
     * Validates request limits by loading AppConfig internally and checking query
     * length and page size.
     *
     * @param queryString
     *            the query string to validate
     * @param pageSize
     *            the page size to validate
     * @throws IllegalArgumentException
     *             if validation fails
     */
    public static void validateRequestLimits(String queryString, int pageSize) {
        if (limits == null) {
            return;
        }
        validateQueryLength(queryString, limits.getMaxQueryLength());
        validatePageSize(pageSize, limits.getMaxSize());
    }

    /**
     * Validates that the pageSize does not exceed the maximum allowed value.
     *
     * @param pageSize
     *            the number of results per page
     * @param maxSize
     *            the maximum allowed pageSize
     * @throws IllegalArgumentException
     *             if pageSize exceeds the limit
     */
    public static void validatePageSize(int pageSize, int maxSize) {
        if (pageSize > maxSize) {
            throw new IllegalArgumentException(
                    String.format("Requested pageSize (%d) exceeds maximum allowed (%d)", pageSize, maxSize));
        }
    }

    /**
     * Validates that the query string length does not exceed maxQueryLength.
     *
     * @param query
     *            the query string
     * @param maxQueryLength
     *            the maximum allowed query length
     * @throws IllegalArgumentException
     *             if query length > maxQueryLength
     */
    public static void validateQueryLength(String query, int maxQueryLength) {
        if (query != null && query.length() > maxQueryLength) {
            throw new IllegalArgumentException(
                    String.format("Query length (%d) exceeds maximum allowed (%d)", query.length(), maxQueryLength));
        }
    }
}
