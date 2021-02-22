package com.teamscale.buildbreaker.exceptions;

public class SslConnectionFailureException extends BuildBreakerExceptionBase {
    public SslConnectionFailureException(String s) {
        super(s, -2);
    }
}
