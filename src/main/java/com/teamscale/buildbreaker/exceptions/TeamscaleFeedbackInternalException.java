package com.teamscale.buildbreaker.exceptions;

public class TeamscaleFeedbackInternalException extends IllegalStateException {
    public TeamscaleFeedbackInternalException(String s, Throwable e) {
        super(s, e);
    }

    public TeamscaleFeedbackInternalException(String s) {
        super(s);
    }
}
