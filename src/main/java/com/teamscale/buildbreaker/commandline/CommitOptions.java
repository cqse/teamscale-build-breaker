package com.teamscale.buildbreaker.commandline;

import picocli.CommandLine;

import static com.teamscale.buildbreaker.commandline.BranchAndTimestampUtils.validateBranchAndTimestamp;

class CommitOptions {

    /**
     * The branch and timestamp info for the queried commit. May be <code>null</code>.
     */
    public String branchAndTimestamp;

    @CommandLine.Option(names = {"-b", "--branch-and-timestamp"}, paramLabel = "<branch:timestamp>",
            description = "The branch and Unix Epoch timestamp for which analysis results should be evaluated." +
                    " This is typically the branch and commit timestamp of the commit that the current CI pipeline" +
                    " is building. The timestamp must be milliseconds since" +
                    " 00:00:00 UTC Thursday, 1 January 1970." + "\nFormat: BRANCH:TIMESTAMP" +
                    "\nExample: master:1597845930000" + "\nExample: master:1597845930000")
    public void setBranchAndTimestamp(String branchAndTimestamp) {
        validateBranchAndTimestamp(branchAndTimestamp, "-b, --branch-and-timestamp");
        this.branchAndTimestamp = branchAndTimestamp;
    }

    /**
     * The revision (hash) of the queried commit. May be <code>null</code>.
     */
    @CommandLine.Option(names = {"-c", "--commit"}, paramLabel = "<commit-revision>",
            description = "The version control commit revision for which analysis results should be obtained." +
                    " This is typically the commit that the current CI pipeline is building." +
                    " Can be either a Git SHA1, a SVN revision number or a Team Foundation changeset ID.")
    public String commit;
}
