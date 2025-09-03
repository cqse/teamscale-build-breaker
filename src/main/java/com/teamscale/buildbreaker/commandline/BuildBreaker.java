package com.teamscale.buildbreaker.commandline;

import com.teamscale.buildbreaker.commandline.autodetect_revision.EnvironmentVariableChecker;
import com.teamscale.buildbreaker.commandline.autodetect_revision.GitChecker;
import com.teamscale.buildbreaker.commandline.autodetect_revision.SvnChecker;
import com.teamscale.buildbreaker.commandline.exceptions.AnalysisNotFinishedException;
import com.teamscale.buildbreaker.commandline.exceptions.ExceptionToExitCodeMapper;
import com.teamscale.buildbreaker.commandline.exceptions.InvalidParametersException;
import com.teamscale.buildbreaker.commandline.exceptions.PrintExceptionMessageHandler;
import com.teamscale.buildbreaker.commandline.exceptions.SslConnectionFailureException;
import com.teamscale.buildbreaker.evaluation.EvaluationResult;
import com.teamscale.buildbreaker.evaluation.Finding;
import com.teamscale.buildbreaker.evaluation.FindingsEvaluator;
import com.teamscale.buildbreaker.evaluation.MetricViolation;
import com.teamscale.buildbreaker.evaluation.MetricsEvaluator;
import com.teamscale.buildbreaker.teamscale_client.TeamscaleClient;
import com.teamscale.buildbreaker.teamscale_client.exceptions.CommitCouldNotBeResolvedException;
import com.teamscale.buildbreaker.teamscale_client.exceptions.HttpRedirectException;
import com.teamscale.buildbreaker.teamscale_client.exceptions.HttpStatusCodeException;
import com.teamscale.buildbreaker.teamscale_client.exceptions.ParserException;
import com.teamscale.buildbreaker.teamscale_client.exceptions.RepositoryNotFoundException;
import com.teamscale.buildbreaker.teamscale_client.exceptions.TooManyCommitsException;
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
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Duration;
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
                " --branch-and-timestamp master:1597845930000.\nYou can also compare the current branch with a target branch using" +
                " --target-branch-and-timestamp to evaluate findings between branches or --base-branch-and-timestamp for a commit range" +
                " instead of just for a single commit.")
public class BuildBreaker implements Callable<Integer> {

    /**
     * The command spec models how this executable can be called. It is automatically injected by PicoCli.
     */
    @Spec
    static CommandSpec spec;

    private HttpUrl teamscaleServerUrl;

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
    private String user;

    @Option(names = {"-a", "--accesskey"}, paramLabel = "<accesskey>", required = true,
            description = "The IDE access key of the given user. Can be retrieved in Teamscale under Admin > Users.")
    private String accessKey;

    @Option(names = {"-p", "--project"}, required = true,
            description = "The project ID or alias (NOT the project name!) relevant for the analysis.")
    private String project;

    @ArgGroup(multiplicity = "1")
    private CommitOptions commitOptions;

    @ArgGroup(exclusive = false)
    private ThresholdEvalOptions thresholdEvalOptions;

    @ArgGroup(exclusive = false)
    private FindingEvalOptions findingEvalOptions;

    @Option(names = {"--wait-for-analysis-timeout"}, paramLabel = "<iso-8601-duration>",
            description = "The duration this tool will wait for analysis of the given commit to be finished in Teamscale, given in ISO-8601 format (e.g., PT20m for 20 minutes or PT30s for 30 seconds). This is useful when Teamscale starts analyzing at the same time this tool is called, and analysis is not yet finished. Default value is 20 minutes.")
    public Duration waitForAnalysisTimeoutDuration = Duration.ofMinutes(20);

    @Option(names = {"--repository-url"}, paramLabel = "<remote-repository-url>",
            description = "The URL of the remote repository where the analyzed commit originated. This is required in case a commit hook event should be sent to Teamscale for this repository if the repository URL cannot be established from the build environment.")
    public String remoteRepositoryUrl;

    @ArgGroup()
    private SslConnectionOptions sslConnectionOptions;

    /**
     * Caches the commit which should be analyzed.
     */
    String detectedCommit = null;
    private TeamscaleClient teamscaleClient;


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
        if (!findingEvalOptions.evaluateFindings && !thresholdEvalOptions.evaluateThresholds) {
            throw new InvalidParametersException(
                    "Please specify at least one of --evaluate-findings or --evaluate-thresholds, otherwise no evaluation will take place.");
        }
        OkHttpClient okHttpClient = OkHttpClientUtils
                .createClient(sslConnectionOptions.disableSslValidation, sslConnectionOptions.keyStorePath,
                        sslConnectionOptions.keyStorePassword);
        teamscaleClient = new TeamscaleClient(okHttpClient, teamscaleServerUrl, user, accessKey, project);
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
        } catch (UnknownHostException e) {
            fail("The host " + teamscaleServerUrl + " could not be resolved. Please ensure you have no typo and that" +
                    " this host is reachable from this server. " + e.getMessage());
        } catch (ConnectException e) {
            fail("The URL " + teamscaleServerUrl + " refused a connection. Please ensure that you have no typo and that" +
                    " this endpoint is reachable and not blocked by firewalls. " + e.getMessage());
        } catch (HttpRedirectException e) {
            fail("You provided an incorrect URL. The server responded with a redirect to " + "'" + e.getRedirectLocation() + "'." +
                    " This may e.g. happen if you used HTTP instead of HTTPS." +
                    " Please use the correct URL for Teamscale instead.");
        } catch (HttpStatusCodeException e) {
            handleHttpStatusCodeException(e);
        } catch (CommitCouldNotBeResolvedException e) {
            // We do not call fail here because we want to keep the old api of returning code -5
            System.out.println("Could not resolve revision " + e.getRevision() +
                    " to a valid commit known to Teamscale (no commits returned)");
            return -5;
        } catch (TooManyCommitsException e) {
            fail("Could not resolve revision " + e.getRevision() +
                    " to a valid commit known to Teamscale (too many commits returned): " + e.getCommitDescriptorsJson());
        } catch (IOException e) {
            fail("Encountered an error while communicating with Teamscale: ");
        } finally {
            // we must shut down OkHttp as otherwise it will leave threads running and
            // prevent JVM shutdown
            teamscaleClient.close();
        }
        return -9000; // Should never be reached
    }

    private EvaluationResult evaluateFindings() throws IOException, TooManyCommitsException, HttpRedirectException, HttpStatusCodeException, CommitCouldNotBeResolvedException, ParserException, InterruptedException {
        String currentBranchAndTimestamp = determineBranchAndTimestamp();
        String targetBranchAndTimestamp = determineTargetBranchAndTimestamp();
        String baseBranchAndTimestamp = determineBaseBranchAndTimestamp();

        if (!StringUtils.isEmpty(targetBranchAndTimestamp) && !StringUtils.isEmpty(baseBranchAndTimestamp)) {
            throw new InvalidParametersException("Cannot use both --target-revision/--target-branch-and-timestamp and --base-revision/--base-branch-and-timestamp options at the same time.");
        }

        Pair<List<Finding>, List<Finding>> findingAssessments;
        if (StringUtils.isEmpty(targetBranchAndTimestamp) && StringUtils.isEmpty(baseBranchAndTimestamp)) {
            System.out.println("Evaluating findings for the current commit...");
            findingAssessments = teamscaleClient.fetchFindingsUsingCommitDetails(currentBranchAndTimestamp);
        } else if (!StringUtils.isEmpty(targetBranchAndTimestamp)) {
            waitForAnalysisToFinish(targetBranchAndTimestamp);
            System.out.println("Evaluating findings by comparing the current commit with target commit '" +
                    targetBranchAndTimestamp + "'...");
            findingAssessments = teamscaleClient.fetchFindingsUsingBranchMergeDelta(currentBranchAndTimestamp, targetBranchAndTimestamp);
        } else {
            waitForAnalysisToFinish(baseBranchAndTimestamp);
            System.out.println("Evaluating findings by aggregating the findings from the base commit '" +
                    baseBranchAndTimestamp + "'...");
            findingAssessments = teamscaleClient.fetchFindingsUsingLinearDelta(baseBranchAndTimestamp, currentBranchAndTimestamp);
        }

        EvaluationResult findingsResult = new FindingsEvaluator()
                .evaluate(findingAssessments, findingEvalOptions.failOnYellowFindings,
                        findingEvalOptions.failOnModified);
        System.out.println(findingsResult);

        if (findingsResult.toStatusCode() > 0) {
            System.out.println(
                    "More detailed information about these findings is available in Teamscale's web interface at " +
                            buildFindingsUiUrl(targetBranchAndTimestamp, baseBranchAndTimestamp, currentBranchAndTimestamp));
        }

        return findingsResult;
    }

    private HttpUrl buildFindingsUiUrl(String targetBranchAndTimestamp, String baseBranchAndTimestamp, String currentBranchAndTimestamp) {
        HttpUrl.Builder urlBuilder;
        if (StringUtils.isEmpty(targetBranchAndTimestamp) && StringUtils.isEmpty(baseBranchAndTimestamp)) {
            urlBuilder = teamscaleServerUrl.newBuilder()
                    .addPathSegment("activity.html")
                    .fragment("details/" + project + "?t=" + currentBranchAndTimestamp);
        } else if (!StringUtils.isEmpty(targetBranchAndTimestamp)) {
            urlBuilder = teamscaleServerUrl.newBuilder().addPathSegment("delta")
                    .addPathSegment("findings")
                    .addPathSegment(project)
                    .addQueryParameter("from", currentBranchAndTimestamp)
                    .addQueryParameter("to", targetBranchAndTimestamp)
                    .addQueryParameter("showMergeFindings", "true")
                    .addQueryParameter("finding-section", "1") // Show red findings section
                    .addQueryParameter("filter-option", "EXCLUDED"); // Hide flagged findings
        } else {
            urlBuilder = teamscaleServerUrl.newBuilder().addPathSegment("delta")
                    .addPathSegment("findings")
                    .addPathSegment(project)
                    .addQueryParameter("from", baseBranchAndTimestamp)
                    .addQueryParameter("to", currentBranchAndTimestamp)
                    .addQueryParameter("finding-section", "1") // Show red findings section
                    .addQueryParameter("filter-option", "EXCLUDED"); // Hide flagged findings
        }
        return urlBuilder.build();
    }

    private EvaluationResult evaluateMetrics() throws IOException, HttpRedirectException, HttpStatusCodeException, TooManyCommitsException, CommitCouldNotBeResolvedException, ParserException {
        System.out.println("Evaluating thresholds...");
        String currentBranchAndTimestamp = determineBranchAndTimestamp();
        List<MetricViolation> metricAssessments = teamscaleClient.fetchMetricAssessments(currentBranchAndTimestamp, thresholdEvalOptions.thresholdConfig);
        EvaluationResult metricResult =
                new MetricsEvaluator().evaluate(metricAssessments, thresholdEvalOptions.failOnYellowMetrics);
        System.out.println(metricResult);
        if (metricResult.toStatusCode() > 0) {
            HttpUrl.Builder urlBuilder = teamscaleServerUrl.newBuilder().addPathSegment("metrics.html")
                    .fragment("/" + project + "?t=" + currentBranchAndTimestamp);
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

    private void waitForAnalysisToFinish(String branchAndTimestampToWaitFor) throws IOException, InterruptedException, HttpRedirectException, HttpStatusCodeException {
        LocalDateTime timeout = LocalDateTime.now().plus(waitForAnalysisTimeoutDuration);
        boolean teamscaleAnalysisFinished = teamscaleClient.isTeamscaleAnalysisFinished(branchAndTimestampToWaitFor);
        if (!teamscaleAnalysisFinished) {
            System.out.println(
                    "The commit that should be evaluated has not yet been analyzed on the Teamscale instance. Triggering Teamscale commit hook on repository.");
            try {
                teamscaleClient.triggerCommitHookEvent(remoteRepositoryUrl);
                System.out.println("Commit hook triggered successfully.");
            } catch (RepositoryNotFoundException e) {
                System.out.println(
                        "Failed to automatically detect the remote repository URL. Please specify it manually via --repository-url to enable sending a commit hook event to Teamscale.");
            } catch (IOException e) {
                System.out.println("Failure when trying to send the commit hook event to Teamscale: " + e);
            }

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

    private String determineBranchAndTimestamp() throws IOException, TooManyCommitsException, HttpRedirectException, HttpStatusCodeException, CommitCouldNotBeResolvedException {
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

    private String detectCommit() {
        List<Supplier<String>> commitDetectionStrategies =
                List.of(() -> detectedCommit, EnvironmentVariableChecker::findCommit, GitChecker::findCommit,
                        SvnChecker::findRevision);
        Optional<String> optionalCommit =
                commitDetectionStrategies.stream().map(Supplier::get).filter(Objects::nonNull).findFirst();
        optionalCommit.ifPresent(commit -> detectedCommit = commit);
        return detectedCommit;
    }

    private String determineTargetBranchAndTimestamp() throws IOException, TooManyCommitsException, HttpRedirectException, HttpStatusCodeException, CommitCouldNotBeResolvedException {
        if (!StringUtils.isEmpty(findingEvalOptions.targetRevision)) {
            return teamscaleClient.fetchTimestampForRevision(findingEvalOptions.targetRevision);
        } else if (!StringUtils.isEmpty(findingEvalOptions.targetBranchAndTimestamp)) {
            return findingEvalOptions.targetBranchAndTimestamp;
        } else {
            return "";
        }
    }

    private String determineBaseBranchAndTimestamp() throws IOException, TooManyCommitsException, HttpRedirectException, HttpStatusCodeException, CommitCouldNotBeResolvedException {
        if (!StringUtils.isEmpty(findingEvalOptions.baseRevision)) {
            return teamscaleClient.fetchTimestampForRevision(findingEvalOptions.baseRevision);
        } else if (!StringUtils.isEmpty(findingEvalOptions.baseBranchAndTimestamp)) {
            return findingEvalOptions.baseBranchAndTimestamp;
        } else {
            return "";
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

    private void handleHttpStatusCodeException(HttpStatusCodeException e) {
        switch (e.getStatusCode()) {
            case 401:
                HttpUrl editUserUrl = teamscaleServerUrl.newBuilder()
                        .addPathSegment("admin.html#users")
                        .addQueryParameter("action", "edit")
                        .addQueryParameter("username", user)
                        .build();
                failWithHttpResponse("You provided incorrect credentials." + " Either the user '" + user + "' does not exist in Teamscale" +
                        " or the access key you provided is incorrect." +
                        " Please check both the username and access key in Teamscale under Admin > Users:" + " " +
                        editUserUrl + "\nPlease use the user's access key, not their password.", e.getResponseBody());

            case 403:
                failWithHttpResponse("The user user '" + user + "' is not allowed to upload data to the Teamscale project '" + project +
                        "'." + " Please grant this user the 'Perform External Uploads' permission in Teamscale" +
                        " under Project Configuration > Projects by clicking on the button showing three" +
                        " persons next to project '" + project + "'.", e.getResponseBody());
            case 404:
                HttpUrl projectPerspectiveUrl = teamscaleServerUrl.newBuilder()
                        .addPathSegment("project.html")
                        .build();
                failWithHttpResponse("The project with ID or alias '" + project + "' does not seem to exist in Teamscale." +
                                " Please ensure that you used the project ID or the project alias, NOT the project name." +
                                " You can see the IDs of all projects at " + projectPerspectiveUrl +
                                "\nPlease also ensure that the Teamscale URL is correct and no proxy is required to access it.",
                        e.getResponseBody());

            default:
                failWithHttpResponse("Unexpected response from Teamscale", e.getResponseBody());

        }
    }

    public void fail(String message) {
        throw new ParameterException(spec.commandLine(), message);
    }

    private void failWithHttpResponse(String message, String responseBody) {
        String message1 = "Program execution failed:\n\n" + message + "\n\nTeamscale's response:\n" + responseBody;
        fail(message1);
    }
}
