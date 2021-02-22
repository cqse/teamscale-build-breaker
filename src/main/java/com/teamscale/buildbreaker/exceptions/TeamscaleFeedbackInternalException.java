package com.teamscale.buildbreaker.exceptions;

public class TeamscaleFeedbackInternalException extends BuildBreakerExceptionBase {

    private static final int ERROR_CODE = -4;

    public TeamscaleFeedbackInternalException(String s, Throwable e) {
        super(s, e, -4);
    }

    public TeamscaleFeedbackInternalException(String s) {
        super(s, ERROR_CODE);
    }
}
