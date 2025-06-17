package com.teamscale.buildbreaker.client.exceptions;

public abstract class CommitResolutionExceptionBase extends Exception {
    private final String revision;

    public CommitResolutionExceptionBase(String revision) {
        this.revision = revision;
    }

    public String getRevision() {
        return revision;
    }
}
