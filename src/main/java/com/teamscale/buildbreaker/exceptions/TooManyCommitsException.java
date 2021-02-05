package com.teamscale.buildbreaker.exceptions;

public class TooManyCommitsException extends RuntimeException {
    public TooManyCommitsException(String s) {
        super(s);
    }
}
