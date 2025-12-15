package com.danieljhkim.dsearch.gateway.api;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.danieljhkim.dsearch.common.exception.IndexInitializationException;
import com.danieljhkim.dsearch.common.exception.IndexOperationException;
import com.danieljhkim.dsearch.common.exception.InvalidIndexStateException;
import com.danieljhkim.dsearch.common.exception.ParseGoneWrongException;
import com.danieljhkim.dsearch.common.exception.ServiceException;
import com.danieljhkim.dsearch.common.exception.ShardNotFoundException;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger LOGGER = Logger.getLogger(GlobalExceptionHandler.class.getName());

	// ---------- Domain exceptions (shared) ----------

	@ExceptionHandler(ShardNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleShardNotFound(
			ShardNotFoundException ex,
			HttpServletRequest request) {

		HttpStatus status = HttpStatus.NOT_FOUND;
		ErrorResponse body = new ErrorResponse(
				status.value(),
				status.getReasonPhrase(),
				ex.getMessage(),
				request.getRequestURI());
		return new ResponseEntity<>(body, status);
	}

	@ExceptionHandler(ParseGoneWrongException.class)
	public ResponseEntity<ErrorResponse> handleParseGoneWrong(
			ParseGoneWrongException ex,
			HttpServletRequest request) {

		HttpStatus status = HttpStatus.BAD_REQUEST;
		ErrorResponse body = new ErrorResponse(
				status.value(),
				status.getReasonPhrase(),
				ex.getMessage(),
				request.getRequestURI());
		return new ResponseEntity<>(body, status);
	}

	@ExceptionHandler(InvalidIndexStateException.class)
	public ResponseEntity<ErrorResponse> handleInvalidIndexState(
			InvalidIndexStateException ex,
			HttpServletRequest request) {

		HttpStatus status = HttpStatus.PRECONDITION_FAILED;
		ErrorResponse body = new ErrorResponse(
				status.value(),
				status.getReasonPhrase(),
				ex.getMessage(),
				request.getRequestURI());
		return new ResponseEntity<>(body, status);
	}

	@ExceptionHandler(IndexInitializationException.class)
	public ResponseEntity<ErrorResponse> handleIndexInit(
			IndexInitializationException ex,
			HttpServletRequest request) {

		HttpStatus status = HttpStatus.PRECONDITION_FAILED;
		ErrorResponse body = new ErrorResponse(
				status.value(),
				status.getReasonPhrase(),
				"Index initialization failed: " + ex.getMessage(),
				request.getRequestURI());
		return new ResponseEntity<>(body, status);
	}

	@ExceptionHandler(IndexOperationException.class)
	public ResponseEntity<ErrorResponse> handleIndexOp(
			IndexOperationException ex,
			HttpServletRequest request) {

		HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
		ErrorResponse body = new ErrorResponse(
				status.value(),
				status.getReasonPhrase(),
				"Index operation failed: " + ex.getMessage(),
				request.getRequestURI());
		return new ResponseEntity<>(body, status);
	}

	@ExceptionHandler(ServiceException.class)
	public ResponseEntity<ErrorResponse> handleGenericIndexService(
			ServiceException ex,
			HttpServletRequest request) {

		HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
		ErrorResponse body = new ErrorResponse(
				status.value(),
				status.getReasonPhrase(),
				ex.getMessage(),
				request.getRequestURI());
		return new ResponseEntity<>(body, status);
	}

	// ---------- gRPC exceptions from index-node ----------

	@ExceptionHandler(StatusRuntimeException.class)
	public ResponseEntity<ErrorResponse> handleGrpcStatus(
			StatusRuntimeException ex,
			HttpServletRequest request) {

		Status status = ex.getStatus();
		HttpStatus httpStatus = mapGrpcStatusToHttp(status.getCode());

		if (httpStatus.is5xxServerError()) {
			LOGGER.log(Level.SEVERE, "Downstream gRPC error: " + status, ex);
		} else {
			LOGGER.log(Level.WARNING, "Downstream gRPC error: " + status, ex);
		}

		String message = status.getDescription() != null
				? status.getDescription()
				: status.getCode().name();

		ErrorResponse body = new ErrorResponse(
				httpStatus.value(),
				httpStatus.getReasonPhrase(),
				message,
				request.getRequestURI());

		return new ResponseEntity<>(body, httpStatus);
	}

	private HttpStatus mapGrpcStatusToHttp(Status.Code code) {
		return switch (code) {
			case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
			case NOT_FOUND -> HttpStatus.NOT_FOUND;
			case ALREADY_EXISTS -> HttpStatus.CONFLICT;
			case FAILED_PRECONDITION -> HttpStatus.PRECONDITION_FAILED;
			case OUT_OF_RANGE -> HttpStatus.BAD_REQUEST;
			case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
			case PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
			case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
			case DEADLINE_EXCEEDED -> HttpStatus.GATEWAY_TIMEOUT;
			case RESOURCE_EXHAUSTED -> HttpStatus.TOO_MANY_REQUESTS;
			default -> HttpStatus.INTERNAL_SERVER_ERROR;
		};
	}

	// ---------- Validation / Spring MVC errors ----------

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(
			MethodArgumentNotValidException ex,
			HttpServletRequest request) {

		HttpStatus status = HttpStatus.BAD_REQUEST;

		String message = ex.getBindingResult().getFieldErrors()
				.stream()
				.map(err -> err.getField() + " " + err.getDefaultMessage())
				.collect(Collectors.joining("; "));

		ErrorResponse body = new ErrorResponse(
				status.value(),
				status.getReasonPhrase(),
				message,
				request.getRequestURI());
		return new ResponseEntity<>(body, status);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ErrorResponse> handleConstraintViolation(
			ConstraintViolationException ex,
			HttpServletRequest request) {

		HttpStatus status = HttpStatus.BAD_REQUEST;

		String message = ex.getConstraintViolations()
				.stream()
				.map(v -> v.getPropertyPath() + " " + v.getMessage())
				.collect(Collectors.joining("; "));

		ErrorResponse body = new ErrorResponse(
				status.value(),
				status.getReasonPhrase(),
				message,
				request.getRequestURI());
		return new ResponseEntity<>(body, status);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(
			HttpMessageNotReadableException ex,
			HttpServletRequest request) {
		HttpStatus status = HttpStatus.BAD_REQUEST;
		ex.getMostSpecificCause();
		String detail = ex.getMostSpecificCause().getMessage();

		ErrorResponse body = new ErrorResponse(
				status.value(),
				status.getReasonPhrase(),
				detail,
				request.getRequestURI());
		return new ResponseEntity<>(body, status);
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ErrorResponse> handleIllegalArgument(
			IllegalArgumentException ex,
			HttpServletRequest request) {
		HttpStatus status = HttpStatus.BAD_REQUEST;
		ErrorResponse body = new ErrorResponse(
				status.value(),
				status.getReasonPhrase(),
				ex.getMessage(),
				request.getRequestURI());
		return new ResponseEntity<>(body, status);
	}

	// ---------- Fallback ----------

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleGenericException(
			Exception ex,
			HttpServletRequest request) {

		LOGGER.log(Level.SEVERE, "Unhandled exception in gateway", ex);

		HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
		ErrorResponse body = new ErrorResponse(
				status.value(),
				status.getReasonPhrase(),
				"Internal server error",
				request.getRequestURI());
		return new ResponseEntity<>(body, status);
	}
}