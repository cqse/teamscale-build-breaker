package com.teamscale.buildbreaker.client.exceptions;

public class ParserException extends Exception {
    public ParserException(String message, Throwable cause) {
        super(message, cause);
    }

    public ParserException(Throwable cause) {
        super(cause);
    }
}
