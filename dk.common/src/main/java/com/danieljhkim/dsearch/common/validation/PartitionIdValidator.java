package com.danieljhkim.dsearch.common.validation;

import java.util.regex.Pattern;

/**
 * Validates partitionId values before they are used to derive filesystem paths, so that path
 * separators and traversal sequences (e.g. {@code ../}) are rejected before they ever reach a
 * shard directory resolution.
 */
public final class PartitionIdValidator {

    private static final Pattern VALID_PARTITION_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private PartitionIdValidator() {
        // Utility class - prevent instantiation
    }

    /**
     * Validates that partitionId is non-null and matches the allowlisted pattern
     * {@code [A-Za-z0-9_-]{1,64}}, which cannot contain path separators or {@code ..}.
     *
     * @param partitionId the partitionId to validate
     * @throws IllegalArgumentException if partitionId is null or does not match the allowlist
     */
    public static void validate(String partitionId) {
        if (partitionId == null || !VALID_PARTITION_ID.matcher(partitionId).matches()) {
            throw new IllegalArgumentException(
                    "partitionId must match ^[A-Za-z0-9_-]{1,64}$ and must not contain path separators or '..'");
        }
    }
}
