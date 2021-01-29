package com.teamscale.buildbreaker.exceptions;

import picocli.CommandLine;

public class ExceptionToExitCodeMapper implements CommandLine.IExitCodeExceptionMapper {
    @Override
    public int getExitCode(Throwable t) {
        if (t instanceof SslConnectionFailureException) {
            return -2;
        } else if (t instanceof KeystoreException) {
            return -3;
        } else if (t instanceof TeamscaleFeedbackInternalException) {
            return -4;
        } else if (t instanceof CommitCouldNotBeResolvedException) {
            return -5;
        } else if (t instanceof AnalysisNotFinishedException) {
            return -6;
        }
        return -1;
    }
}
