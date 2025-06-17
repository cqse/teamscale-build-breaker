package com.teamscale.buildbreaker;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.teamscale.buildbreaker.autodetect_revision.GitChecker;
import com.teamscale.buildbreaker.autodetect_revision.SvnChecker;
import com.teamscale.buildbreaker.exceptions.AnalysisNotFinishedException;
import com.teamscale.buildbreaker.exceptions.CommitCouldNotBeResolvedException;
import com.teamscale.buildbreaker.exceptions.TooManyCommitsException;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.conqat.lib.commons.string.StringUtils;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// TODO return actual objects in the findings and metric fetching methods
public class TeamscaleClient implements AutoCloseable {
    private final OkHttpClient client;
    private final HttpUrl teamscaleServerUrl;
    private final String user;
    private final String accessKey;
    private final String project;
    private final Consumer<String> failHandler;
    Map<String, String> timestampRevisionCache = new HashMap<>();

    public TeamscaleClient(OkHttpClient client, HttpUrl teamscaleServerUrl, String user, String accessKey, String project, Consumer<String> failHandler) {
        this.client = client;
        this.teamscaleServerUrl = teamscaleServerUrl;
        this.user = user;
        this.accessKey = accessKey;
        this.project = project;
        this.failHandler = failHandler;
    }

    // TODO docs
    public String fetchFindingsUsingLinearDelta(String startBranchAndTimestamp, String endBranchAndTimestamp) throws IOException {
        HttpUrl.Builder builder =
                teamscaleServerUrl.newBuilder().addPathSegments("api/projects").addPathSegment(project)
                        .addPathSegments("findings/delta")
                        .addQueryParameter("t2", endBranchAndTimestamp)
                        .addQueryParameter("t1", startBranchAndTimestamp)
                        .addQueryParameter("uniform-path", "");

        HttpUrl url = builder.build();
        Request request = createAuthenticatedGetRequest(url);
        return sendRequest(url, request);
    }

    /**
     * Fetches findings using the original finding-churn/list endpoint.
     * <p>
     * This method uses Teamscale's finding-churn/list endpoint to get findings for a specific commit.
     * It retrieves findings that were added or modified in the specified commit.
     *
     * @return The JSON response from the finding-churn/list endpoint containing the findings
     * @throws IOException If there's an error communicating with the Teamscale server
     */
    public String fetchFindingsUsingCommitDetails(String branchAndTimestamp) throws IOException {
        HttpUrl.Builder builder =
                teamscaleServerUrl.newBuilder().addPathSegments("api/projects").addPathSegment(project)
                        .addPathSegments("finding-churn/list")
                        .addQueryParameter("t", branchAndTimestamp);
        HttpUrl url = builder.build();
        Request request = createAuthenticatedGetRequest(url);
        return sendRequest(url, request);
    }

    /**
     * Fetches findings using the delta service to compare with a target branch.
     * <p>
     * This method uses Teamscale's delta service to compare the current branch/commit with a target branch.
     * It sets up the API call with the appropriate parameters:
     * <ul>
     *   <li>t1: The target branch with HEAD timestamp (starting point for comparison)</li>
     *   <li>t2: The current branch and timestamp (endpoint for comparison)</li>
     *   <li>uniform-path: Empty string to include all paths</li>
     * </ul>
     * The delta service returns findings that were added, removed, or changed between the two branches.
     *
     * @return The JSON response from the delta service containing the finding differences
     * @throws IOException If there's an error communicating with the Teamscale server
     */
    public String fetchFindingsUsingBranchMergeDelta(String sourceBranchAndTimestamp, String targetBranchAndTimestamp) throws IOException {

        HttpUrl.Builder builder =
                teamscaleServerUrl.newBuilder().addPathSegments("api/projects").addPathSegment(project)
                        .addPathSegments("merge-requests/finding-churn");

        builder.addQueryParameter("source", sourceBranchAndTimestamp);
        builder.addQueryParameter("target", targetBranchAndTimestamp);

        HttpUrl url = builder.build();
        Request request = createAuthenticatedGetRequest(url);
        return sendRequest(url, request);
    }

    public String fetchMetricAssessments(String branchAndTimestamp, String thresholdConfig) throws IOException {
        HttpUrl.Builder builder =
                teamscaleServerUrl.newBuilder().addPathSegments("api/projects").addPathSegment(project)
                        .addPathSegment("metric-assessments").addQueryParameter("uniform-path", "")
                        .addQueryParameter("t", branchAndTimestamp)
                        .addQueryParameter("configuration-name", thresholdConfig);
        HttpUrl url = builder.build();
        Request request = createAuthenticatedGetRequest(url);
        return sendRequest(url, request);
    }

    public String fetchTimestampForRevision(String revision) throws IOException {
        if (timestampRevisionCache.containsKey(revision)) {
            return timestampRevisionCache.get(revision);
        }
        HttpUrl.Builder builder =
                teamscaleServerUrl.newBuilder().addPathSegments("api/projects").addPathSegment(project)
                        .addPathSegment("revision").addPathSegment(revision).addPathSegment("commits");
        HttpUrl url = builder.build();
        Request request = createAuthenticatedGetRequest(url);
        String commitDescriptorsJson = sendRequest(url, request);
        long braceCount = commitDescriptorsJson.chars().filter(c -> c == '{').count();
        if (braceCount == 0) {
            throw new CommitCouldNotBeResolvedException("Could not resolve revision " + revision +
                    " to a valid commit known to Teamscale (no commits returned)");
        }
        if (braceCount > 1) {
            throw new TooManyCommitsException("Could not resolve revision " + revision +
                    " to a valid commit known to Teamscale (too many commits returned): " + commitDescriptorsJson);
        }
        String timestamp = extractTimestamp(commitDescriptorsJson);
        String branchName = extractBranchname(commitDescriptorsJson);

        String branchWithTimestamp = branchName + ":" + timestamp;
        timestampRevisionCache.put(revision, branchWithTimestamp);
        return branchWithTimestamp;
    }


    public void waitForAnalysisToFinish(Duration waitForAnalysisTimeoutDuration, String branchAndTimestampToWaitFor, String remoteRepositoryUrl) throws IOException, InterruptedException {
        LocalDateTime timeout = LocalDateTime.now().plus(waitForAnalysisTimeoutDuration);
        boolean teamscaleAnalysisFinished = isTeamscaleAnalysisFinished(branchAndTimestampToWaitFor);
        if (!teamscaleAnalysisFinished) {
            System.out.println(
                    "The commit that should be evaluated has not yet been analyzed on the Teamscale instance. Triggering Teamscale commit hook on repository.");
            triggerCommitHookEvent(remoteRepositoryUrl);
        }
        while (!teamscaleAnalysisFinished && LocalDateTime.now().isBefore(timeout)) {
            System.out.println(
                    "The commit that should be evaluated has not yet been analyzed on the Teamscale instance. Will retry in ten seconds until the timeout is reached at " +
                            DateTimeFormatter.RFC_1123_DATE_TIME.format(timeout.atZone(ZoneOffset.UTC)) +
                            ". You can change this timeout using --wait-for-analysis-timeout.");
            Thread.sleep(Duration.ofSeconds(10).toMillis());
            teamscaleAnalysisFinished = isTeamscaleAnalysisFinished(branchAndTimestampToWaitFor);
        }
        if (!teamscaleAnalysisFinished) {
            throw new AnalysisNotFinishedException(
                    "The commit that should be evaluated was not analyzed by Teamscale in time before the analysis timeout.");
        }
    }

    private String extractTimestamp(String commitDescriptorsJson) {
        Pattern pattern = Pattern.compile("\"timestamp\"\\s*:\\s*(\\d+)\\b");
        Matcher matcher = pattern.matcher(commitDescriptorsJson);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new CommitCouldNotBeResolvedException("Could not parse commit descriptor JSON: " + commitDescriptorsJson);
    }

    private String extractBranchname(String commitDescriptorsJson) {
        Pattern pattern = Pattern.compile("\"branchName\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(commitDescriptorsJson);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new CommitCouldNotBeResolvedException("Could not parse commit descriptor JSON: " + commitDescriptorsJson);
    }

    private boolean isTeamscaleAnalysisFinished(String branchAndTimestampToWaitFor) throws IOException {
        try {
            String[] branchAndTimestamp = branchAndTimestampToWaitFor.split(":", 2);
            String branch = branchAndTimestamp[0];
            long timestamp = Long.parseLong(branchAndTimestamp[1]);
            return isAnalysisFinished(branch, timestamp);
        } catch (CommitCouldNotBeResolvedException e) {
            return false;
        }
    }

    private boolean isAnalysisFinished(String branch, long timestamp) throws IOException {
        HttpUrl.Builder builder =
                teamscaleServerUrl.newBuilder().addPathSegments("api/projects").addPathSegment(project)
                        .addPathSegment("branch-analysis-state").addPathSegment(branch);
        HttpUrl url = builder.build();
        Request request = createAuthenticatedGetRequest(url);
        String analysisStateJson = sendRequest(url, request);
        DocumentContext analysisState = JsonPath.parse(analysisStateJson);
        try {
            Integer lastFinishedTimestamp = analysisState.read("$.timestamp");
            return lastFinishedTimestamp >= timestamp;
        } catch (ClassCastException e) {
            Long lastFinishedTimestamp = analysisState.read("$.timestamp");
            return lastFinishedTimestamp >= timestamp;
        }
    }

    private String sendRequest(HttpUrl url, Request request) throws IOException {
        try (Response response = client.newCall(request).execute()) {
            handleErrors(response);
            return OkHttpClientUtils.readBodySafe(response);
        } catch (UnknownHostException e) {
            fail("The host " + url + " could not be resolved. Please ensure you have no typo and that" +
                    " this host is reachable from this server. " + e.getMessage());
        } catch (ConnectException e) {
            fail("The URL " + url + " refused a connection. Please ensure that you have no typo and that" +
                    " this endpoint is reachable and not blocked by firewalls. " + e.getMessage());
        }
        // Never reached
        throw new IllegalStateException("This state should never be reached");

    }

    private Request createAuthenticatedGetRequest(HttpUrl url) {
        return new Request.Builder().header("Authorization", Credentials.basic(user, accessKey)).url(url).get().build();
    }

    private void handleErrors(Response response) {
        if (response.isRedirect()) {
            String location = response.header("Location");
            if (location == null) {
                location = "<server did not provide a location header>";
            }
            fail("You provided an incorrect URL. The server responded with a redirect to " + "'" + location + "'." +
                    " This may e.g. happen if you used HTTP instead of HTTPS." +
                    " Please use the correct URL for Teamscale instead.", response);
        }

        if (response.code() == 401) {
            HttpUrl editUserUrl = teamscaleServerUrl.newBuilder().addPathSegment("admin.html#users")
                    .addQueryParameter("action", "edit").addQueryParameter("username", user).build();
            fail("You provided incorrect credentials." + " Either the user '" + user + "' does not exist in Teamscale" +
                    " or the access key you provided is incorrect." +
                    " Please check both the username and access key in Teamscale under Admin > Users:" + " " +
                    editUserUrl + "\nPlease use the user's access key, not their password.", response);
        }

        if (response.code() == 403) {
            // can't include a URL to the corresponding Teamscale screen since that page does not support aliases
            // and the user may have provided an alias, so we'd be directing them to a red error page in that case
            fail("The user user '" + user + "' is not allowed to upload data to the Teamscale project '" + project +
                    "'." + " Please grant this user the 'Perform External Uploads' permission in Teamscale" +
                    " under Project Configuration > Projects by clicking on the button showing three" +
                    " persons next to project '" + project + "'.", response);
        }

        if (response.code() == 404) {
            HttpUrl projectPerspectiveUrl = teamscaleServerUrl.newBuilder().addPathSegment("project.html").build();
            fail("The project with ID or alias '" + project + "' does not seem to exist in Teamscale." +
                            " Please ensure that you used the project ID or the project alias, NOT the project name." +
                            " You can see the IDs of all projects at " + projectPerspectiveUrl +
                            "\nPlease also ensure that the Teamscale URL is correct and no proxy is required to access it.",
                    response);
        }

        if (!response.isSuccessful()) {
            fail("Unexpected response from Teamscale", response);
        }
    }

    /**
     * Notifies Teamscale that the repository has been updated. This means that analysis of the new commit will start
     * promptly.
     */
    private void triggerCommitHookEvent(String remoteRepositoryUrl) {
        String repositoryUrl = determineRemoteRepositoryUrl(remoteRepositoryUrl);
        if (StringUtils.isEmpty(repositoryUrl)) {
            return;
        }
        HttpUrl.Builder builder = teamscaleServerUrl.newBuilder().addPathSegments("api/post-commit-hook")
                .addQueryParameter("repository", repositoryUrl);
        HttpUrl url = builder.build();
        Request request = new Request.Builder().header("Authorization", Credentials.basic(user, accessKey)).url(url)
                .post(RequestBody.create(null, new byte[]{})).build();
        try {
            sendRequest(url, request);
            System.out.println("Commit hook triggered successfully.");
        } catch (IOException e) {
            System.out.println("Failure when trying to send the commit hook event to Teamscale: " + e);
        }
    }

    private String determineRemoteRepositoryUrl(String remoteRepositoryUrl) {
        String finalRemoteRepositoryUrl = remoteRepositoryUrl;
        List<Supplier<String>> repoUrlDetectionStrategies =
                List.of(() -> finalRemoteRepositoryUrl, GitChecker::findRepoUrl, SvnChecker::findRepoUrl);
        Optional<String> optionalUrl =
                repoUrlDetectionStrategies.stream().map(Supplier::get).filter(Objects::nonNull).findFirst();
        if (!optionalUrl.isPresent()) {
            System.out.println(
                    "Failed to automatically detect the remote repository URL. Please specify it manually via --repository-url to enable sending a commit hook event to Teamscale.");
            return null;
        }
        remoteRepositoryUrl = optionalUrl.get();
        return remoteRepositoryUrl;
    }


    private void fail(String message) {
        failHandler.accept(message);
    }

    private void fail(String message, Response response) {
        String message1 = "Program execution failed:\n\n" + message + "\n\nTeamscale's response:\n" + response.toString() + "\n" +
                OkHttpClientUtils.readBodySafe(response);
        fail(message1);
    }


    @Override
    public void close() {
        client.dispatcher().executorService().shutdownNow();
        client.connectionPool().evictAll();

    }
}
