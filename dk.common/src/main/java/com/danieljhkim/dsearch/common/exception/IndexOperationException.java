package com.danieljhkim.dsearch.common.exception;

// lucene read, del, commit failures
public class IndexOperationException extends ServiceException {

	public IndexOperationException(String message) {
		super(message);
	}

	public IndexOperationException(String message, Throwable cause) {
		super(message, cause);
	}
}