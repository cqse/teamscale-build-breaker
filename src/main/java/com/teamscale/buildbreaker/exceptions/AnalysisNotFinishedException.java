package com.teamscale.buildbreaker.exceptions;

public class AnalysisNotFinishedException extends BuildBreakerExceptionBase {
    public AnalysisNotFinishedException(String s) {
        super(s, -6);
    }
}
