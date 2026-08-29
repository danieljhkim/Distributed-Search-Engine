package com.danieljhkim.dsearch.common.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PartitionIdValidatorTest {

    @Test
    void acceptsSafePartitionIdentifiersAtBothLengthBoundaries() {
        assertDoesNotThrow(() -> PartitionIdValidator.validate("a"));
        assertDoesNotThrow(() -> PartitionIdValidator.validate("A_9-" + "x".repeat(60)));
    }

    @Test
    void rejectsNullTraversalSeparatorsAndOverlongIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> PartitionIdValidator.validate(null));
        assertThrows(IllegalArgumentException.class, () -> PartitionIdValidator.validate("../secrets"));
        assertThrows(IllegalArgumentException.class, () -> PartitionIdValidator.validate("partition/name"));
        assertThrows(IllegalArgumentException.class, () -> PartitionIdValidator.validate("x".repeat(65)));
    }
}
