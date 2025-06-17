package com.teamscale.buildbreaker;

import com.teamscale.buildbreaker.autodetect_revision.EnvironmentVariableChecker;
import com.teamscale.buildbreaker.autodetect_revision.GitChecker;
import com.teamscale.buildbreaker.autodetect_revision.SvnChecker;
import com.teamscale.buildbreaker.evaluation.EvaluationResult;
import com.teamscale.buildbreaker.evaluation.Finding;
import com.teamscale.buildbreaker.evaluation.FindingsEvaluator;
import com.teamscale.buildbreaker.evaluation.MetricViolation;
import com.teamscale.buildbreaker.evaluation.MetricsEvaluator;
import com.teamscale.buildbreaker.exceptions.AnalysisNotFinishedException;
import com.teamscale.buildbreaker.exceptions.ExceptionToExitCodeMapper;
import com.teamscale.buildbreaker.exceptions.InvalidParametersException;
import com.teamscale.buildbreaker.exceptions.PrintExceptionMessageHandler;
import com.teamscale.buildbreaker.exceptions.SslConnectionFailureException;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.conqat.lib.commons.collections.Pair;
import org.conqat.lib.commons.string.StringUtils;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

@Command(name = "teamscale-buildbreaker", mixinStandardHelpOptions = true, version = "teamscale-buildbreaker 0.1",
        description = "Queries a Teamscale server for analysis results, evaluates them and emits a corresponding status code.",
        footer = "\nBy default, this tries to automatically detect the code commit" +
                " for which to obtain feedback from environment variables or a Git or SVN checkout in the" +
                " current working directory. This feature" +
                " supports many common CI tools like Jenkins, GitLab, GitHub Actions, Travis CI etc." +
                " If automatic detection fails, you can manually specify either a commit via --commit, or" +
                " a branch and timestamp via --branch-and-timestamp.\nIntroduction of new findings can only be evaluated when" +
                " a specific commit is given, but threshold evaluation can also be performed on the current version of a branch by using" +
                " --branch-and-timestamp my-branch:HEAD.\nYou can also compare the current branch with a target branch using" +
                " --target-branch to evaluate findings between branches instead of just for a single commit.")
public class BuildBreaker implements Callable<Integer> {

    /**
     * The command spec models how this executable can be called. It is automatically injected by PicoCli.
     */
    @Spec
    static CommandSpec spec;

    /**
     * The base URL of the Teamscale server
     */
    private HttpUrl teamscaleServerUrl;

    /**
     * The ID or alias of the Teamscale project
     */
    @Option(names = {"-p", "--project"}, required = true,
            description = "The project ID or alias (NOT the project name!) relevant for the analysis.")
    private String project;

    /**
     * Whether to evaluate thresholds, and detail options for that evaluation
     */
    @ArgGroup(exclusive = false)
    private ThresholdEvalOptions thresholdEvalOptions;

    /**
     * Caches the commit which should be analyzed.
     */
    String detectedCommit = null;

    public static class ThresholdEvalOptions {
        /**
         * Whether to evaluate thresholds
         */
        @Option(names = {"-t", "--evaluate-thresholds"}, required = true,
                description = "If this option is set, metrics from a given threshold profile will be evaluated.")
        public boolean evaluateThresholds;

        /**
         * The threshold config to use
         */
        @Option(names = {"-o", "--threshold-config"}, required = true,
                description = "The name of the threshold config that should be used. Needs to be set if --evaluate-thresholds is active.")
        public String thresholdConfig;

        /**
         * Whether to fail on yellow metrics
         */
        @Option(names = {"--fail-on-yellow-metrics"},
                description = "Whether to fail on yellow metrics (with exit code 2). Can only be used if --evaluate-thresholds is active.")
        public boolean failOnYellowMetrics;
    }

    /**
     * Whether to evaluate findings, and detail options for that evaluation
     */
    @ArgGroup(exclusive = false)
    private FindingEvalOptions findingEvalOptions;

    public static class FindingEvalOptions {
        /**
         * Whether to evaluate findings
         */
        @Option(names = {"-f", "--evaluate-findings"}, required = true,
                description = "If this option is set, findings introduced with the given commit will be evaluated.")
        public boolean evaluateFindings;

        /**
         * Whether to fail on yellow findings
         */
        @Option(names = {"--fail-on-yellow-findings"},
                description = "Whether to fail on yellow findings (with exit code 2). Can only be used if --evaluate-findings is active.")
        public boolean failOnYellowFindings;

        /**
         * Whether to fail on findings in modified code
         */
        @Option(names = {"--fail-on-modified-code-findings"},
                description = "Fail on findings in modified code (not just new findings). Can only be used if --evaluate-findings is active.")
        public boolean failOnModified;

        /**
         * The target branch to compare with
         */
        @Option(names = {"--target-commit"},
                description = "The commit to compare with using Teamscale's branch merge delta service. If specified, findings will be evaluated based on what would happen if the commit specified via --commit would be merged into this commit.")
        // TODO validation
        public String targetCommit;

        /**
         * The target branch to compare with
         */
        @Option(names = {"--base-commit"},
                description = "The base commit to compare with using Teamscale's linear delta service. The commit needs to be a parent of the one specified via --commit. If specified, findings of all commits in between the two will be evaluated.")
        // TODO validation
        // TODO readme doc
        // TODO this cannot handle commit hashes, should it? Maybe add a second option as for --commit and --branch-and-timestamp?
        public String baseCommit;
    }

    /**
     * The username of the Teamscale user performing the query
     */
    private String user;

    /**
     * The access key of the Teamscale user performing the query
     */
    private String accessKey;

    /**
     * The options specifying the queried commit.
     */
    @ArgGroup(exclusive = true, multiplicity = "1")
    private CommitOptions commitOptions;

    private static class CommitOptions {

        /**
         * The branch and timestamp info for the queried commit. May be <code>null</code>.
         */
        private String branchAndTimestamp;

        @Option(names = {"-b", "--branch-and-timestamp"}, paramLabel = "<branch:timestamp>",
                description = "The branch and Unix Epoch timestamp for which analysis results should be evaluated." +
                        " This is typically the branch and commit timestamp of the commit that the current CI pipeline" +
                        " is building. The timestamp must be milliseconds since" +
                        " 00:00:00 UTC Thursday, 1 January 1970 or the string 'HEAD' to evaluate thresholds on" +
                        " the latest revision on that branch." + "\nFormat: BRANCH:TIMESTAMP" +
                        "\nExample: master:1597845930000" + "\nExample: develop:HEAD")
        public void setBranchAndTimestamp(String branchAndTimestamp) {
            validateBranchAndTimestamp(branchAndTimestamp);
            this.branchAndTimestamp = branchAndTimestamp;
        }

        /**
         * The revision (hash) of the queried commit. May be <code>null</code>.
         */
        @Option(names = {"-c", "--commit"}, paramLabel = "<commit-revision>",
                description = "The version control commit revision for which analysis results should be obtained." +
                        " This is typically the commit that the current CI pipeline is building." +
                        " Can be either a Git SHA1, a SVN revision number or a Team Foundation changeset ID.")
        private String commit;

        private void validateBranchAndTimestamp(String branchAndTimestamp) throws ParameterException {
            if (StringUtils.isEmpty(branchAndTimestamp)) {
                return;
            }

            String[] parts = branchAndTimestamp.split(":", 2);
            if (parts.length == 1) {
                throw new ParameterException(spec.commandLine(),
                        "You specified an invalid branch and timestamp" + " with --branch-and-timestamp: " +
                                branchAndTimestamp + "\nYou must  use the" +
                                " format BRANCH:TIMESTAMP, where TIMESTAMP is a Unix timestamp in milliseconds" +
                                " or the string 'HEAD' (to upload to the latest commit on that branch).");
            }

            String timestampPart = parts[1];
            if (timestampPart.equalsIgnoreCase("HEAD")) {
                return;
            }

            validateTimestamp(timestampPart);
        }

        private void validateTimestamp(String timestampPart) throws ParameterException {
            try {
                long unixTimestamp = Long.parseLong(timestampPart);
                if (unixTimestamp < 10000000000L) {
                    String millisecondDate = DateTimeFormatter.RFC_1123_DATE_TIME
                            .format(Instant.ofEpochMilli(unixTimestamp).atZone(ZoneOffset.UTC));
                    String secondDate = DateTimeFormatter.RFC_1123_DATE_TIME
                            .format(Instant.ofEpochSecond(unixTimestamp).atZone(ZoneOffset.UTC));
                    throw new ParameterException(spec.commandLine(),
                            "You specified an invalid timestamp with" + " --branch-and-timestamp. The timestamp '" +
                                    timestampPart + "'" + " is equal to " + millisecondDate +
                                    ". This is probably not what" +
                                    " you intended. Most likely you specified the timestamp in seconds," +
                                    " instead of milliseconds. If you use " + timestampPart + "000" +
                                    " instead, it will mean " + secondDate);
                }
            } catch (NumberFormatException e) {
                throw new ParameterException(spec.commandLine(), "You specified an invalid timestamp with" +
                        " --branch-and-timestamp. Expected either 'HEAD' or a unix timestamp" +
                        " in milliseconds since 00:00:00 UTC Thursday, 1 January 1970, e.g." +
                        " master:1606743774000\nInstead you used: " + timestampPart);
            }
        }
    }

    private TeamscaleClient teamscaleClient;

    @Option(names = {"-s", "--server"}, paramLabel = "<teamscale-server-url>", required = true,
            description = "The URL under which the Teamscale server can be reached.")
    public void setTeamscaleServerUrl(String teamscaleServerUrl) {
        this.teamscaleServerUrl = HttpUrl.parse(teamscaleServerUrl);
        if (this.teamscaleServerUrl == null) {
            throw new ParameterException(spec.commandLine(),
                    "The URL you entered is not well-formed: " + teamscaleServerUrl);
        }
    }

    @Option(names = {"-u", "--user"}, required = true,
            description = "The user that performs the query. Requires VIEW permission on the queried project.")
    public void setUser(String user) {
        this.user = user;
    }

    @Option(names = {"-a", "--accesskey"}, paramLabel = "<accesskey>", required = true,
            description = "The IDE access key of the given user. Can be retrieved in Teamscale under Admin > Users.")
    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    /**
     * The duration to wait for Teamscale analysis of the commit to finish up.
     */
    @Option(names = {"--wait-for-analysis-timeout"}, paramLabel = "<iso-8601-duration>", required = false,
            description = "The duration this tool will wait for analysis of the given commit to be finished in Teamscale, given in ISO-8601 format (e.g., PT20m for 20 minutes or PT30s for 30 seconds). This is useful when Teamscale starts analyzing at the same time this tool is called, and analysis is not yet finished. Default value is 20 minutes.")
    public Duration waitForAnalysisTimeoutDuration = Duration.ofMinutes(20);

    /**
     * The URL of the remote repository used to send a commit hook event to Teamscale.
     */
    @Option(names = {"--repository-url"}, paramLabel = "<remote-repository-url>", required = false,
            description = "The URL of the remote repository where the analyzed commit originated. This is required in case a commit hook event should be sent to Teamscale for this repository if the repository URL cannot be established from the build environment.")
    public String remoteRepositoryUrl;

    @ArgGroup(exclusive = true)
    private SslConnectionOptions sslConnectionOptions;

    private class SslConnectionOptions {
        @Option(names = "--insecure",
                description = "By default, SSL certificates are validated against the configured KeyStore." +
                        " This flag disables validation which makes using this tool with self-signed certificates easier.")
        private boolean disableSslValidation;

        private String keyStorePath;

        private String keyStorePassword;

        @Option(names = "--trusted-keystore", paramLabel = "<keystore-path;password>",
                description = "A Java KeyStore file and its corresponding password. The KeyStore contains" +
                        " additional certificates that should be trusted when performing SSL requests." +
                        " Separate the path from the password with a semicolon, e.g:" +
                        "\n/path/to/keystore.jks;PASSWORD" +
                        "\nThe path to the KeyStore must not contain a semicolon. Cannot be used in conjunction with --disable-ssl-validation.")
        public void setKeyStorePathAndPassword(String keystoreAndPassword) {
            String[] keystoreAndPasswordSplit = keystoreAndPassword.split(";", 2);
            this.keyStorePath = keystoreAndPasswordSplit[0];
            if (StringUtils.isEmpty(this.keyStorePath)) {
                throw new ParameterException(spec.commandLine(), "You must supply a valid KeyStore path.");
            }
            this.keyStorePassword = keystoreAndPasswordSplit[1];
        }

    }

    public static void main(String... args) {
        // Just let PicoCLI handle everything. Main entry point for PicoCLI is the "call()" method.
        int exitCode =
                new CommandLine(new BuildBreaker()).setExecutionExceptionHandler(new PrintExceptionMessageHandler())
                        .setExitCodeExceptionMapper(new ExceptionToExitCodeMapper()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        initDefaultOptions();
        if (!findingEvalOptions.evaluateFindings && !thresholdEvalOptions.failOnYellowMetrics) {
            throw new InvalidParametersException(
                    "Please specify at least one of --evaluate-findings or --evaluate-thresholds, otherwise no evaluation will take place.");
        }
        OkHttpClient okHttpClient = OkHttpClientUtils
                .createClient(sslConnectionOptions.disableSslValidation, sslConnectionOptions.keyStorePath,
                        sslConnectionOptions.keyStorePassword);
        teamscaleClient = new TeamscaleClient(okHttpClient, teamscaleServerUrl, user, accessKey, project, this::fail);
        EvaluationResult aggregatedResult = new EvaluationResult();

        try {
            waitForAnalysisToFinish(determineBranchAndTimestamp());
            if (thresholdEvalOptions.evaluateThresholds) {
                aggregatedResult.addAll(evaluateMetrics());
            }

            if (findingEvalOptions.evaluateFindings) {
                aggregatedResult.addAll(evaluateFindings());
            }
            return aggregatedResult.toStatusCode();
        } catch (SSLHandshakeException e) {
            handleSslConnectionFailure(e);
            return -1;
        } finally {
            // we must shut down OkHttp as otherwise it will leave threads running and
            // prevent JVM shutdown
            teamscaleClient.close();
        }

    }

    private EvaluationResult evaluateFindings() throws IOException {
        if (!StringUtils.isEmpty(findingEvalOptions.targetCommit) && !StringUtils.isEmpty(findingEvalOptions.baseCommit)) {
            throw new InvalidParametersException("Cannot use both --target-commit and --base-commit options at the same time.");
        }
        if (StringUtils.isEmpty(findingEvalOptions.targetCommit) && StringUtils.isEmpty(findingEvalOptions.baseCommit)) {
            System.out.println("Evaluating findings for the current commit...");
        } else if (!StringUtils.isEmpty(findingEvalOptions.targetCommit)) {
            System.out.println("Evaluating findings by comparing the current commit with target commit '" +
                    findingEvalOptions.targetCommit + "'...");
        } else {
            System.out.println("Evaluating findings by aggregating the findings from the base commit '" +
                    findingEvalOptions.baseCommit + "'...");
        }

        Pair<List<Finding>, List<Finding>> findingAssessments = fetchFindings();
        EvaluationResult findingsResult = new FindingsEvaluator()
                .evaluate(findingAssessments, findingEvalOptions.failOnYellowFindings,
                        findingEvalOptions.failOnModified);
        System.out.println(findingsResult);

        if (findingsResult.toStatusCode() > 0) {
            HttpUrl.Builder urlBuilder;

            if (!StringUtils.isEmpty(findingEvalOptions.targetCommit)) {
                // For branch comparison, link to delta
                String sourceBranchHead = determineBranchAndTimestamp();
                String targetBranchAndTimestamp = findingEvalOptions.targetCommit;
                urlBuilder = teamscaleServerUrl.newBuilder().addPathSegment("delta")
                        .addPathSegment("findings")
                        .addPathSegment(project)
                        .addQueryParameter("from", sourceBranchHead)
                        .addQueryParameter("to", targetBranchAndTimestamp)
                        .addQueryParameter("showMergeFindings", "true")
                        .addQueryParameter("finding-section", "1") // Show red findings section
                        .addQueryParameter("filter-option", "EXCLUDED"); // Hide flagged findings

            } else if (!StringUtils.isEmpty(findingEvalOptions.baseCommit)) {
                // For branch comparison, link to delta.html
                String currentBranchAndTimestamp = determineBranchAndTimestamp();
                String baseBranchAndTimestamp = findingEvalOptions.baseCommit;
                urlBuilder = teamscaleServerUrl.newBuilder().addPathSegment("delta")
                        .addPathSegment("findings")
                        .addPathSegment(project)
                        .addQueryParameter("from", baseBranchAndTimestamp)
                        .addQueryParameter("to", currentBranchAndTimestamp)
                        .addQueryParameter("finding-section", "1") // Show red findings section
                        .addQueryParameter("filter-option", "EXCLUDED"); // Hide flagged findings
            } else {
                // For single commit evaluation, link to activity.html
                urlBuilder = teamscaleServerUrl.newBuilder().addPathSegment("activity.html")
                        .fragment("details/" + project + "?t=" + determineBranchAndTimestamp());
            }

            System.out.println(
                    "More detailed information about these findings is available in Teamscale's web interface at " +
                            urlBuilder.build());
        }

        return findingsResult;
    }

    private EvaluationResult evaluateMetrics() throws IOException {
        System.out.println("Evaluating thresholds...");
        List<MetricViolation> metricAssessments = teamscaleClient.fetchMetricAssessments(determineBranchAndTimestamp(), thresholdEvalOptions.thresholdConfig);
        EvaluationResult metricResult =
                new MetricsEvaluator().evaluate(metricAssessments, thresholdEvalOptions.failOnYellowMetrics);
        System.out.println(metricResult);
        if (metricResult.toStatusCode() > 0) {
            HttpUrl.Builder urlBuilder = teamscaleServerUrl.newBuilder().addPathSegment("metrics.html")
                    .fragment("/" + project + "?t=" + determineBranchAndTimestamp());
            System.out.println(
                    "More detailed information about these metrics is available in Teamscale's web interface at " +
                            urlBuilder.build());
        }
        return metricResult;
    }

    private void initDefaultOptions() {
        if (sslConnectionOptions == null) {
            sslConnectionOptions = new SslConnectionOptions();
        }
        if (findingEvalOptions == null) {
            findingEvalOptions = new FindingEvalOptions();
        }
        if (thresholdEvalOptions == null) {
            thresholdEvalOptions = new ThresholdEvalOptions();
        }
    }

    private Pair<List<Finding>, List<Finding>> fetchFindings() throws IOException {
        if (!StringUtils.isEmpty(findingEvalOptions.targetCommit)) {
            // Use the delta service when a target branch is specified
            return teamscaleClient.fetchFindingsUsingBranchMergeDelta(determineBranchAndTimestamp(), findingEvalOptions.targetCommit);
        } else if (!StringUtils.isEmpty(findingEvalOptions.baseCommit)) {
            return teamscaleClient.fetchFindingsUsingLinearDelta(findingEvalOptions.baseCommit, determineBranchAndTimestamp());
        } else {
            // Use the original finding-churn/list endpoint when no target branch is specified
            return teamscaleClient.fetchFindingsUsingCommitDetails(determineBranchAndTimestamp());
        }
    }

    private void waitForAnalysisToFinish(String branchAndTimestampToWaitFor) throws IOException, InterruptedException {
        LocalDateTime timeout = LocalDateTime.now().plus(waitForAnalysisTimeoutDuration);
        boolean teamscaleAnalysisFinished = teamscaleClient.isTeamscaleAnalysisFinished(branchAndTimestampToWaitFor);
        if (!teamscaleAnalysisFinished) {
            System.out.println(
                    "The commit that should be evaluated has not yet been analyzed on the Teamscale instance. Triggering Teamscale commit hook on repository.");
            teamscaleClient.triggerCommitHookEvent(remoteRepositoryUrl);
        }
        while (!teamscaleAnalysisFinished && LocalDateTime.now().isBefore(timeout)) {
            System.out.println(
                    "The commit that should be evaluated has not yet been analyzed on the Teamscale instance. Will retry in ten seconds until the timeout is reached at " +
                            DateTimeFormatter.RFC_1123_DATE_TIME.format(timeout.atZone(ZoneOffset.UTC)) +
                            ". You can change this timeout using --wait-for-analysis-timeout.");
            Thread.sleep(Duration.ofSeconds(10).toMillis());
            teamscaleAnalysisFinished = teamscaleClient.isTeamscaleAnalysisFinished(branchAndTimestampToWaitFor);
        }
        if (!teamscaleAnalysisFinished) {
            throw new AnalysisNotFinishedException(
                    "The commit that should be evaluated was not analyzed by Teamscale in time before the analysis timeout.");
        }
    }

    private String determineBranchAndTimestamp() throws IOException {
        if (!StringUtils.isEmpty(commitOptions.commit)) {
            return teamscaleClient.fetchTimestampForRevision(commitOptions.commit);
        }
        if (!StringUtils.isEmpty(commitOptions.branchAndTimestamp)) {
            return commitOptions.branchAndTimestamp;
        } else {
            // auto-detect if neither option is given
            String commit = detectCommit();
            if (commit == null) {
                throw new ParameterException(spec.commandLine(),
                        "Failed to automatically detect the commit. Please specify it manually via --commit or --branch-and-timestamp");
            }

            return teamscaleClient.fetchTimestampForRevision(commit);
        }
    }

    public void handleSslConnectionFailure(SSLHandshakeException e) {
        if (!StringUtils.isEmpty(sslConnectionOptions.keyStorePath)) {
            throw new SslConnectionFailureException(
                    "Failed to connect via HTTPS to " + teamscaleServerUrl + ": " + e.getMessage() +
                            "\nYou enabled certificate validation and provided a keystore with certificates" +
                            " that should be considered valid. Still, the connection failed." +
                            " Most likely, you did not provide the correct certificates in the keystore" +
                            " or some certificates are missing from it." +
                            "\nPlease also ensure that your Teamscale instance is reachable under " +
                            teamscaleServerUrl +
                            " and that it is configured for HTTPS, not HTTP. E.g. open that URL in your" +
                            " browser and verify that you can connect successfully.");
        } else if (sslConnectionOptions.disableSslValidation) {
            throw new SslConnectionFailureException(
                    "Failed to connect via HTTPS to " + teamscaleServerUrl + ": " + e.getMessage() +
                            "\nYou enabled certificate validation. Most likely, your certificate" +
                            " is either self-signed or your root CA's certificate is not known to" +
                            " teamscale-upload. Please provide the path to a keystore that contains" +
                            " the necessary public certificates that should be trusted by" +
                            " teamscale-upload via --trusted-keystore. You can create a Java keystore" +
                            " with your certificates as described here:" +
                            " https://docs.teamscale.com/howto/connecting-via-https/#using-self-signed-certificates" +
                            "\nPlease also ensure that your Teamscale instance is reachable under " +
                            teamscaleServerUrl +
                            " and that it is configured for HTTPS, not HTTP. E.g. open that URL in your" +
                            " browser and verify that you can connect successfully.");
        } else {
            throw new SslConnectionFailureException(
                    "Failed to connect via HTTPS to " + teamscaleServerUrl + ": " + e.getMessage() +
                            "\nPlease ensure that your Teamscale instance is reachable under " + teamscaleServerUrl +
                            " and that it is configured for HTTPS, not HTTP. E.g. open that URL in your" +
                            " browser and verify that you can connect successfully.");
        }
    }

    private String detectCommit() {
        List<Supplier<String>> commitDetectionStrategies =
                List.of(() -> detectedCommit, EnvironmentVariableChecker::findCommit, GitChecker::findCommit,
                        SvnChecker::findRevision);
        Optional<String> optionalCommit =
                commitDetectionStrategies.stream().map(Supplier::get).filter(Objects::nonNull).findFirst();
        optionalCommit.ifPresent(commit -> detectedCommit = commit);
        return detectedCommit;
    }

    /**
     * Print error message and exit the program
     */
    public void fail(String message) {
        throw new ParameterException(spec.commandLine(), message);
    }
}
