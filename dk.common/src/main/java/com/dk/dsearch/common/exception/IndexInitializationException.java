package com.dk.dsearch.common.exception;

// lucene fails to load/open shard
public class IndexInitializationException extends ServiceException {

    public IndexInitializationException(String message) {
        super(message);
    }

    public IndexInitializationException(String message, Throwable cause) {
        super(message, cause);
    }
}