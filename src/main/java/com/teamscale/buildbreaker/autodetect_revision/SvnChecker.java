package com.teamscale.buildbreaker.autodetect_revision;

public class SvnChecker {

    public static String findRevision() {
        if (!isInsideSvn()) {
            System.out.println("The working directory does not appear to be within an SVN repository.");
            return null;
        }

        ProcessUtils.ProcessResult result = ProcessUtils.run("svn", "info", "--show-item", "revision");
        if (result.wasSuccessful()) {
            String revision = result.stdoutAndStdErr.trim();
            System.out.println("Using SVN revision " + revision);
            return revision;
        }

        System.out.println("Failed to read checked-out SVN revision. svn info --show-item revision returned: " +
                result.stdoutAndStdErr);
        return null;
    }

    public static String findRepoUrl() {
        if (!isInsideSvn()) {
            System.out.println("The working directory does not appear to be within an SVN repository.");
            return null;
        }

        ProcessUtils.ProcessResult result = ProcessUtils.run("svn", "info", "--show-item", "repos-root-url");
        if (result.wasSuccessful()) {
            String repoUrl = result.stdoutAndStdErr.trim();
            System.out.println("Using SVN repository URL " + repoUrl);
            return repoUrl;
        }

        System.out.println("Failed to read checked-out SVN revision. svn info --show-item repos-root-url returned: " +
                result.stdoutAndStdErr);
        return null;
    }

    private static boolean isInsideSvn() {
        ProcessUtils.ProcessResult result = ProcessUtils.run("svn", "info");
        return result.wasSuccessful() && result.stdoutAndStdErr.contains("URL:");
    }

}
