package com.teamscale.buildbreaker.exceptions;

public class InvalidParametersException extends BuildBreakerExceptionBase {
    public InvalidParametersException(String s) {
        super(s, -7);
    }
}
