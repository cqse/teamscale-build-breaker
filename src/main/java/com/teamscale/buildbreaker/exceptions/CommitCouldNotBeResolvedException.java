package com.teamscale.buildbreaker.exceptions;

public class CommitCouldNotBeResolvedException extends BuildBreakerExceptionBase {
    public CommitCouldNotBeResolvedException(String s) {
        super(s, -5);
    }
}
