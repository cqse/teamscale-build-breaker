package com.teamscale.buildbreaker.commandline.exceptions;

public class BuildBreakerInternalException extends BuildBreakerExceptionBase {

    private static final int ERROR_CODE = -4;

    public BuildBreakerInternalException(String s, Throwable e) {
        super(s, e, -4);
    }

    public BuildBreakerInternalException(String s) {
        super(s, ERROR_CODE);
    }
}
