package com.teamscale.buildbreaker.commandline.exceptions;

public class SslConnectionFailureException extends BuildBreakerExceptionBase {
    public SslConnectionFailureException(String s) {
        super(s, -2);
    }
}
