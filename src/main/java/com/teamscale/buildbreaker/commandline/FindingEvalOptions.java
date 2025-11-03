package com.teamscale.buildbreaker.commandline;

import picocli.CommandLine.Option;

import static com.teamscale.buildbreaker.commandline.BranchAndTimestampUtils.validateBranchAndTimestamp;

class FindingEvalOptions {
    @Option(names = {"-f", "--evaluate-findings"}, required = true,
            description = "If this option is set, findings introduced with the given commit will be evaluated.")
    public boolean evaluateFindings;

    @Option(names = {"--fail-on-yellow-findings"},
            description = "Whether to fail on yellow findings (with exit code 2). Can only be used if --evaluate-findings is active.")
    public boolean failOnYellowFindings;

    @Option(names = {"--fail-on-modified-code-findings"},
            description = "Fail on findings in modified code (not just new findings). Can only be used if --evaluate-findings is active.")
    public boolean failOnModified;

    @Option(names = {"--target-revision"},
            description = "The revision (hash) to compare with using Teamscale's branch merge delta service. " +
                    "If specified, findings will be evaluated based on what would happen if the commit specified via --commit would be merged into this commit. " +
                    "This will take precedence over --target-branch-and-timestamp.")
    public String targetRevision;

    public String targetBranchAndTimestamp;

    @Option(names = {"--target-branch-and-timestamp"},
            description = "The branch and timestamp to compare with using Teamscale's branch merge delta service. " +
                    "If specified, findings will be evaluated based on what would happen if the commit specified via --commit would be merged into this commit. " +
                    "--target-revision will take precedence over this option if provided.")
    public void setTargetBranchAndTimestamp(String targetBranchAndTimestamp) {
        validateBranchAndTimestamp(targetBranchAndTimestamp, "--target-branch-and-timestamp");
        this.targetBranchAndTimestamp = targetBranchAndTimestamp;
    }

    @Option(names = {"--base-revision"},
            description = "The base revision (hash) to compare with using Teamscale's linear delta service. " +
                    "The commit needs to be a parent of the one specified via --commit. " +
                    "If specified, findings of all commits in between the two will be evaluated. " +
                    "This will take precedence over --base-branch-and-timestamp. ")
    public String baseRevision;

    public String baseBranchAndTimestamp;

    @Option(names = {"--base-branch-and-timestamp"},
            description = "The base branch and timestamp to compare with using Teamscale's linear delta service. " +
                    "The commit needs to be a parent of the one specified via --commit. " +
                    "If specified, findings of all commits in between the two will be evaluated. " +
                    "--base-revision will take precedence over this option if provided.")
    public void setBaseBranchAndTimestamp(String baseBranchAndTimestamp) {
        validateBranchAndTimestamp(baseBranchAndTimestamp, "--base-branch-and-timestamp");
        this.baseBranchAndTimestamp = baseBranchAndTimestamp;
    }
}
