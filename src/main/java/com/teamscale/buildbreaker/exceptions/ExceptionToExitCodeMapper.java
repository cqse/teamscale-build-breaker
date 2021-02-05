package com.teamscale.buildbreaker.exceptions;

import picocli.CommandLine;

public class ExceptionToExitCodeMapper implements CommandLine.IExitCodeExceptionMapper {
    @Override
    public int getExitCode(Throwable t) {
        if (t instanceof BuildBreakerExceptionBase) {
            return ((BuildBreakerExceptionBase) t).getErrorCode();
        }
        return -1;
    }
}
