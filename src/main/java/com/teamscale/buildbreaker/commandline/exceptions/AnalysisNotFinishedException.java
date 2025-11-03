package com.teamscale.buildbreaker.commandline.exceptions;

public class AnalysisNotFinishedException extends BuildBreakerExceptionBase {
    public AnalysisNotFinishedException(String s) {
        super(s, -6);
    }
}
