package com.teamscale.buildbreaker.exceptions;

public class CommitCouldNotBeResolvedException extends RuntimeException {
    public CommitCouldNotBeResolvedException(String s) {
        super(s);
    }
}
