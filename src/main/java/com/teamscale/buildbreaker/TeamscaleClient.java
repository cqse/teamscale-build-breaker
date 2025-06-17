package com.teamscale.buildbreaker;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.teamscale.buildbreaker.autodetect_revision.GitChecker;
import com.teamscale.buildbreaker.autodetect_revision.SvnChecker;
import com.teamscale.buildbreaker.evaluation.Finding;
import com.teamscale.buildbreaker.evaluation.MetricViolation;
import com.teamscale.buildbreaker.evaluation.ProblemCategory;
import com.teamscale.buildbreaker.exceptions.AnalysisNotFinishedException;
import com.teamscale.buildbreaker.exceptions.CommitCouldNotBeResolvedException;
import com.teamscale.buildbreaker.exceptions.ParserException;
import com.teamscale.buildbreaker.exceptions.TooManyCommitsException;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.conqat.lib.commons.collections.Pair;
import org.conqat.lib.commons.string.StringUtils;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * TODO
 */
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

    /**
     * TODO
     *
     * @param startBranchAndTimestamp
     * @param endBranchAndTimestamp
     * @return pair of added and changed findings (that order)
     * @throws IOException
     */
    public Pair<List<Finding>, List<Finding>> fetchFindingsUsingLinearDelta(String startBranchAndTimestamp, String endBranchAndTimestamp) throws IOException {
        HttpUrl.Builder builder =
                teamscaleServerUrl.newBuilder().addPathSegments("api/projects").addPathSegment(project)
                        .addPathSegments("findings/delta")
                        .addQueryParameter("t2", endBranchAndTimestamp)
                        .addQueryParameter("t1", startBranchAndTimestamp)
                        .addQueryParameter("uniform-path", "");

        HttpUrl url = builder.build();
        Request request = createAuthenticatedGetRequest(url);
        String response = sendRequest(url, request);
        return parseFindingResponse(response);
    }


    /**
     * TODO
     *
     * @param branchAndTimestamp
     * @return
     * @throws IOException
     */
    public Pair<List<Finding>, List<Finding>> fetchFindingsUsingCommitDetails(String branchAndTimestamp) throws IOException {
        HttpUrl.Builder builder =
                teamscaleServerUrl.newBuilder()
                        .addPathSegments("api/projects")
                        .addPathSegment(project)
                        .addPathSegments("finding-churn/list")
                        .addQueryParameter("t", branchAndTimestamp);
        HttpUrl url = builder.build();
        Request request = createAuthenticatedGetRequest(url);
        String response = sendRequest(url, request);
        return parseFindingResponse(response);
    }

    /**
     * TODO
     *
     * @param sourceBranchAndTimestamp
     * @param targetBranchAndTimestamp
     * @return
     * @throws IOException
     */
    public Pair<List<Finding>, List<Finding>> fetchFindingsUsingBranchMergeDelta(String sourceBranchAndTimestamp, String targetBranchAndTimestamp) throws IOException {

        HttpUrl.Builder builder =
                teamscaleServerUrl.newBuilder()
                        .addPathSegments("api/projects")
                        .addPathSegment(project)
                        .addPathSegments("merge-requests/finding-churn")
                        .addQueryParameter("source", sourceBranchAndTimestamp)
                        .addQueryParameter("target", targetBranchAndTimestamp);

        HttpUrl url = builder.build();
        Request request = createAuthenticatedGetRequest(url);
        String response = sendRequest(url, request);
        return parseFindingResponse(response);
    }

    /**
     * TODO
     * @param branchAndTimestamp
     * @param thresholdConfig
     * @return
     * @throws IOException
     */
    public List<MetricViolation> fetchMetricAssessments(String branchAndTimestamp, String thresholdConfig) throws IOException {
        HttpUrl.Builder builder =
                teamscaleServerUrl.newBuilder()
                        .addPathSegments("api/projects")
                        .addPathSegment(project)
                        .addPathSegment("metric-assessments")
                        .addQueryParameter("uniform-path", "")
                        .addQueryParameter("t", branchAndTimestamp)
                        .addQueryParameter("configuration-name", thresholdConfig);
        HttpUrl url = builder.build();
        Request request = createAuthenticatedGetRequest(url);
        String response = sendRequest(url, request);
        return parseMetricResponse(response);
    }

    /**
     * TODO
     * @param revision
     * @return
     * @throws IOException
     */
    public String fetchTimestampForRevision(String revision) throws IOException {
        if (timestampRevisionCache.containsKey(revision)) {
            return timestampRevisionCache.get(revision);
        }
        HttpUrl.Builder builder =
                teamscaleServerUrl.newBuilder()
                        .addPathSegments("api/projects")
                        .addPathSegment(project)
                        .addPathSegment("revision")
                        .addPathSegment(revision)
                        .addPathSegment("commits");
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

    /**
     * Notifies Teamscale that the repository has been updated. This means that analysis of the new commit will start
     * promptly.
     */
    public void triggerCommitHookEvent(String remoteRepositoryUrl) {
        String repositoryUrl = determineRemoteRepositoryUrl(remoteRepositoryUrl);
        if (StringUtils.isEmpty(repositoryUrl)) {
            return;
        }
        HttpUrl.Builder builder = teamscaleServerUrl.newBuilder()
                .addPathSegments("api/post-commit-hook")
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

    /**
     * TODO
     * @param branch
     * @param timestamp
     * @return
     * @throws IOException
     */
    public boolean isTeamscaleAnalysisFinished(String branchAndTimestampToWaitFor) throws IOException {
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
                teamscaleServerUrl.newBuilder()
                        .addPathSegments("api/projects")
                        .addPathSegment(project)
                        .addPathSegment("branch-analysis-state")
                        .addPathSegment(branch);
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

    private Pair<List<Finding>, List<Finding>> parseFindingResponse(String response) throws ParserException {
        DocumentContext findingsJson = JsonPath.parse(response);
        Pair<List<Finding>, List<Finding>> result = Pair.createPair(new ArrayList<>(), new ArrayList<>());
        try {
            result.getFirst().addAll(parseFindings(findingsJson.read("$.addedFindings.*")));
            result.getSecond().addAll(parseFindings(findingsJson.read("$.findingsInChangedCode.*")));
        } catch (ParserException | PathNotFoundException e) {
            throw new ParserException("Could not parse JSON response:\n" + response + "\n\nPlease contact CQSE with an error report.", e);
        }
        return result;
    }

    private List<MetricViolation> parseMetricResponse(String response) {
        List<MetricViolation> result = new ArrayList<>();
        DocumentContext metricAssessments = JsonPath.parse(response);
        List<Map<String, Object>> metricViolations = metricAssessments.read("$..metrics.*");
        for (Map<String, Object> metricViolation : metricViolations) {
            Map<String, String> metricThresholds = (Map<String, String>) metricViolation.get("metricThresholds");
            String displayName = metricViolation.get("displayName").toString();
            String yellowThreshold = metricThresholds.get("thresholdYellow");
            String redThreshold = metricThresholds.get("thresholdRed");
            String formattedTextValue = metricViolation.get("formattedTextValue").toString();
            ProblemCategory rating = ProblemCategory.fromRatingString((String) metricViolation.get("rating"));
            result.add(new MetricViolation(displayName, yellowThreshold, redThreshold, formattedTextValue, rating));
        }
        return result;
    }

    private static List<Finding> parseFindings(List<Map<String, Object>> addedFindings) throws ParserException {
        try {
            return addedFindings.stream().map(findingMap -> {
                String id = findingMap.get("id").toString();
                String group = findingMap.get("groupName").toString();
                String category = findingMap.get("categoryName").toString();
                String message = findingMap.get("message").toString();
                String uniformPath = ((Map<String, String>) findingMap.getOrDefault("location", new HashMap<>())).getOrDefault("uniformPath", "<undefined>");
                ProblemCategory assessment = ProblemCategory.fromRatingString((String) findingMap.get("assessment"));
                return new Finding(id, group, category, message, uniformPath, assessment);
            }).collect(Collectors.toList());
        } catch (ClassCastException e) {
            throw new ParserException(e);
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
        return new Request.Builder()
                .header("Authorization", Credentials.basic(user, accessKey))
                .url(url)
                .get()
                .build();
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
            HttpUrl editUserUrl = teamscaleServerUrl.newBuilder()
                    .addPathSegment("admin.html#users")
                    .addQueryParameter("action", "edit")
                    .addQueryParameter("username", user)
                    .build();
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
            HttpUrl projectPerspectiveUrl = teamscaleServerUrl.newBuilder()
                    .addPathSegment("project.html")
                    .build();
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
