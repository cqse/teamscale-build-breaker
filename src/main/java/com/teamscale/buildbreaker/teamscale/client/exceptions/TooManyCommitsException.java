package com.teamscale.buildbreaker.teamscale.client.exceptions;

public class TooManyCommitsException extends CommitResolutionExceptionBase {
    private final String commitDescriptorsJson;

    public TooManyCommitsException(String revision, String commitDescriptorsJson) {
        super(revision);
        this.commitDescriptorsJson = commitDescriptorsJson;
    }

    public String getCommitDescriptorsJson() {
        return commitDescriptorsJson;
    }
}
