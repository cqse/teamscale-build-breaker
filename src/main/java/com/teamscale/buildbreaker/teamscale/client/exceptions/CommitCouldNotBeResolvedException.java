package com.teamscale.buildbreaker.teamscale.client.exceptions;

public class CommitCouldNotBeResolvedException extends CommitResolutionExceptionBase {

    public CommitCouldNotBeResolvedException(String revision) {
        super(revision);
    }
}
