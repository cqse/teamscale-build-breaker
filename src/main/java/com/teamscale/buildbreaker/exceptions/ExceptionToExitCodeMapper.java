package com.teamscale.buildbreaker.exceptions;

import picocli.CommandLine;

// TODO (MP) Could we just create a specialized version of RuntimeException that knows it's return value and let each of the exceptions below extend from that base exception class?
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
