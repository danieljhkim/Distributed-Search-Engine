package com.danieljhkim.dsearch.gateway.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.danieljhkim.dsearch.common.exception.IndexInitializationException;
import com.danieljhkim.dsearch.common.exception.IndexOperationException;
import com.danieljhkim.dsearch.common.exception.InvalidIndexStateException;
import com.danieljhkim.dsearch.common.exception.NodeUnavailableException;
import com.danieljhkim.dsearch.common.exception.ParseGoneWrongException;
import com.danieljhkim.dsearch.common.exception.ServiceException;
import com.danieljhkim.dsearch.common.exception.ShardNotFoundException;
import io.grpc.Status;
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

    @Test
    void mapsDomainExceptionsWithoutExposingUnexpectedDetails() {
        assertStatus(handler.handleShardNotFound(new ShardNotFoundException("shard-a"), request), HttpStatus.NOT_FOUND);
        assertStatus(
                handler.handleParseGoneWrong(new ParseGoneWrongException("bad query"), request),
                HttpStatus.BAD_REQUEST);
        assertStatus(
                handler.handleInvalidIndexState(new InvalidIndexStateException("closed"), request),
                HttpStatus.PRECONDITION_FAILED);
        assertStatus(
                handler.handleIndexInit(new IndexInitializationException("missing"), request),
                HttpStatus.PRECONDITION_FAILED);
        assertStatus(
                handler.handleIndexOp(new IndexOperationException("write failed"), request),
                HttpStatus.INTERNAL_SERVER_ERROR);
        assertStatus(
                handler.handleNodeUnavailable(new NodeUnavailableException("node-a", "offline"), request),
                HttpStatus.SERVICE_UNAVAILABLE);
        assertStatus(
                handler.handleGenericIndexService(new ServiceException("service failed") {}, request),
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void mapsEveryPublicGrpcStatusAndUsesCodeWhenDescriptionIsAbsent() {
        assertGrpcStatus(Status.Code.INVALID_ARGUMENT, HttpStatus.BAD_REQUEST);
        assertGrpcStatus(Status.Code.NOT_FOUND, HttpStatus.NOT_FOUND);
        assertGrpcStatus(Status.Code.ALREADY_EXISTS, HttpStatus.CONFLICT);
        assertGrpcStatus(Status.Code.FAILED_PRECONDITION, HttpStatus.PRECONDITION_FAILED);
        assertGrpcStatus(Status.Code.OUT_OF_RANGE, HttpStatus.BAD_REQUEST);
        assertGrpcStatus(Status.Code.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED);
        assertGrpcStatus(Status.Code.PERMISSION_DENIED, HttpStatus.FORBIDDEN);
        assertGrpcStatus(Status.Code.UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE);
        assertGrpcStatus(Status.Code.DEADLINE_EXCEEDED, HttpStatus.GATEWAY_TIMEOUT);
        assertGrpcStatus(Status.Code.RESOURCE_EXHAUSTED, HttpStatus.TOO_MANY_REQUESTS);

        ResponseEntity<ErrorResponse> response =
                handler.handleGrpcStatus(Status.CANCELLED.asRuntimeException(), request);
        assertStatus(response, HttpStatus.INTERNAL_SERVER_ERROR);
        assertEquals("CANCELLED", response.getBody().getMessage());
    }

    private void assertGrpcStatus(Status.Code code, HttpStatus expected) {
        ResponseEntity<ErrorResponse> response = handler.handleGrpcStatus(
                Status.fromCode(code).withDescription("downstream").asRuntimeException(), request);
        assertStatus(response, expected);
        assertEquals("downstream", response.getBody().getMessage());
    }

    private static void assertStatus(ResponseEntity<ErrorResponse> response, HttpStatus expected) {
        assertEquals(expected, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(expected.value(), response.getBody().getStatus());
        assertEquals("/api/v1/search", response.getBody().getPath());
    }
}
