package com.cqse.buildbreaker.exceptions;

public class SslConnectionFailureException extends RuntimeException {
    public SslConnectionFailureException(String s) {
        super(s);
    }
}
