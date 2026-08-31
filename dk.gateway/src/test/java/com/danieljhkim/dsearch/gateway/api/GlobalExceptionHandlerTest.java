package com.danieljhkim.dsearch.gateway.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.common.exception.SchemaMismatchException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/search");
    }

    @Test
    void schemaMismatchIsPreconditionFailed() {
        SchemaMismatchException ex = SchemaMismatchException.of("embedding.dimension", "384", "768");
        ResponseEntity<ErrorResponse> response = handler.handleSchemaMismatch(ex, request);

        assertEquals(HttpStatus.PRECONDITION_FAILED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(412, response.getBody().getStatus());
        assertTrue(response.getBody().getMessage().contains("embedding.dimension"));
    }

    @Test
    void testHandleIllegalArgumentException() {
        IllegalArgumentException ex = new IllegalArgumentException("Request limit exceeded");
        ResponseEntity<ErrorResponse> response = handler.handleIllegalArgument(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(400, response.getBody().getStatus());
        assertEquals("Request limit exceeded", response.getBody().getMessage());
    }

    @Test
    void testHandleIllegalArgumentException_WithNullMessage() {
        IllegalArgumentException ex = new IllegalArgumentException();
        ResponseEntity<ErrorResponse> response = handler.handleIllegalArgument(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(400, response.getBody().getStatus());
    }
}
