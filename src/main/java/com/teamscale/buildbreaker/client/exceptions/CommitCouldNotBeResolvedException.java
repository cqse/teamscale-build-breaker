package com.teamscale.buildbreaker.client.exceptions;

public class CommitCouldNotBeResolvedException extends CommitResolutionExceptionBase {

    public CommitCouldNotBeResolvedException(String revision) {
        super(revision);
    }
}
