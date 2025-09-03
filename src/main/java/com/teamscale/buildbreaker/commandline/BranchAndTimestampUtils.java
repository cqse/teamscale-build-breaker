package com.teamscale.buildbreaker.commandline;

import org.conqat.lib.commons.string.StringUtils;
import picocli.CommandLine;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

class BranchAndTimestampUtils {

    private BranchAndTimestampUtils() {
        // Prevent instantiation
    }

    public static void validateBranchAndTimestamp(String branchAndTimestamp, String parameterName) throws CommandLine.ParameterException {
        if (StringUtils.isEmpty(branchAndTimestamp)) {
            return;
        }

        String[] parts = branchAndTimestamp.split(":", 2);
        if (parts.length == 1) {
            throw new CommandLine.ParameterException(BuildBreaker.spec.commandLine(),
                    "You specified an invalid branch and timestamp" + " with " + parameterName + ": " +
                            branchAndTimestamp + "\nYou must  use the" +
                            " format BRANCH:TIMESTAMP, where TIMESTAMP is a Unix timestamp in milliseconds.");
        }

        String timestampPart = parts[1];
        validateTimestamp(timestampPart, parameterName);
    }

    private static void validateTimestamp(String timestampPart, String parameterName) throws CommandLine.ParameterException {
        try {
            long unixTimestamp = Long.parseLong(timestampPart);
            if (unixTimestamp < 10000000000L) {
                String millisecondDate = DateTimeFormatter.RFC_1123_DATE_TIME
                        .format(Instant.ofEpochMilli(unixTimestamp).atZone(ZoneOffset.UTC));
                String secondDate = DateTimeFormatter.RFC_1123_DATE_TIME
                        .format(Instant.ofEpochSecond(unixTimestamp).atZone(ZoneOffset.UTC));
                throw new CommandLine.ParameterException(BuildBreaker.spec.commandLine(),
                        "You specified an invalid timestamp with " + parameterName + ". The timestamp '" +
                                timestampPart + "'" + " is equal to " + millisecondDate +
                                ". This is probably not what" +
                                " you intended. Most likely you specified the timestamp in seconds," +
                                " instead of milliseconds. If you use " + timestampPart + "000" +
                                " instead, it will mean " + secondDate);
            }
        } catch (NumberFormatException e) {
            throw new CommandLine.ParameterException(BuildBreaker.spec.commandLine(), "You specified an invalid timestamp with " + parameterName +
                    ". Expected a unix timestamp in milliseconds since 00:00:00 UTC Thursday, 1 January 1970, e.g." +
                    " master:1606743774000\nInstead you used: " + timestampPart);
        }
    }
}
