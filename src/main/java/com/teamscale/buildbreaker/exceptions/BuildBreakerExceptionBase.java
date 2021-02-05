package com.teamscale.buildbreaker.exceptions;

public class BuildBreakerExceptionBase extends RuntimeException {

    protected final int errorCode;

    public BuildBreakerExceptionBase(String message, int errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public BuildBreakerExceptionBase(String message, Throwable cause, int errorCode) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public int getErrorCode() {
        return errorCode;
    }
}
