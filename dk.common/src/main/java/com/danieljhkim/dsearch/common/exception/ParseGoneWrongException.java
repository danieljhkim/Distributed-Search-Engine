package com.danieljhkim.dsearch.common.exception;

public class ParseGoneWrongException extends ServiceException {

    public ParseGoneWrongException() {
        super("Ooopsie. Parsing went wrong.");
    }

    public ParseGoneWrongException(String message) {
        super(message);
    }

    public ParseGoneWrongException(String message, Throwable cause) {
        super(message, cause);
    }
}
