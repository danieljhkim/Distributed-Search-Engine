package com.danieljhkim.dsearch.common.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RequestLimitsValidatorTest {

    @Test
    void testValidatePageSize_WithinLimit() {
        assertDoesNotThrow(() -> RequestLimitsValidator.validatePageSize(10, 100));
        assertDoesNotThrow(() -> RequestLimitsValidator.validatePageSize(100, 100));
        assertDoesNotThrow(() -> RequestLimitsValidator.validatePageSize(0, 100));
    }

    @Test
    void testValidatePageSize_ExceedsLimit() {
        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> RequestLimitsValidator.validatePageSize(101, 100));
        assert exception.getMessage().contains("Requested pageSize (101) exceeds maximum allowed (100)");
    }

    @Test
    void testValidatePageSize_ExceedsLimit_LargeValue() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> RequestLimitsValidator.validatePageSize(10000, 1000));
        assert exception.getMessage().contains("Requested pageSize (10000) exceeds maximum allowed (1000)");
    }

    @Test
    void testValidateQueryLength_WithinLimit() {
        assertDoesNotThrow(() -> RequestLimitsValidator.validateQueryLength("test query", 100));
        assertDoesNotThrow(() -> RequestLimitsValidator.validateQueryLength("a".repeat(100), 100));
        assertDoesNotThrow(() -> RequestLimitsValidator.validateQueryLength("", 100));
    }

    @Test
    void testValidateQueryLength_NullQuery() {
        assertDoesNotThrow(() -> RequestLimitsValidator.validateQueryLength(null, 100));
    }

    @Test
    void testValidateQueryLength_ExceedsLimit() {
        String longQuery = "a".repeat(101);
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> RequestLimitsValidator.validateQueryLength(longQuery, 100));
        assert exception.getMessage().contains("Query length (101) exceeds maximum allowed (100)");
    }

    @Test
    void testValidateQueryLength_ExceedsLimit_LargeValue() {
        String longQuery = "a".repeat(2048);
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> RequestLimitsValidator.validateQueryLength(longQuery, 1024));
        assert exception.getMessage().contains("Query length (2048) exceeds maximum allowed (1024)");
    }
}
