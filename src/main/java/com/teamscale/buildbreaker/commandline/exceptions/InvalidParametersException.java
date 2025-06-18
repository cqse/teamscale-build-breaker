package com.teamscale.buildbreaker.commandline.exceptions;

public class InvalidParametersException extends BuildBreakerExceptionBase {
    public InvalidParametersException(String s) {
        super(s, -7);
    }
}
