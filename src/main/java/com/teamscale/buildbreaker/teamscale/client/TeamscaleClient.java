package com.teamscale.buildbreaker.teamscale.client;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.teamscale.buildbreaker.commandline.autodetect_revision.GitChecker;
import com.teamscale.buildbreaker.commandline.autodetect_revision.SvnChecker;
import com.teamscale.buildbreaker.evaluation.Finding;
import com.teamscale.buildbreaker.evaluation.MetricViolation;
import com.teamscale.buildbreaker.evaluation.ProblemCategory;
import com.teamscale.buildbreaker.teamscale.client.exceptions.CommitCouldNotBeResolvedException;
import com.teamscale.buildbreaker.teamscale.client.exceptions.HttpRedirectException;
import com.teamscale.buildbreaker.teamscale.client.exceptions.HttpStatusCodeException;
import com.teamscale.buildbreaker.teamscale.client.exceptions.ParserException;
import com.teamscale.buildbreaker.teamscale.client.exceptions.RepositoryNotFoundException;
import com.teamscale.buildbreaker.teamscale.client.exceptions.TooManyCommitsException;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.conqat.lib.commons.collections.Pair;
import org.conqat.lib.commons.string.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * This class acts as a client for interacting with the Teamscale API.
 * It provides methods for retrieving findings, metric violations, and synchronizing information
 * about repository changes with the Teamscale server.
 */
public class TeamscaleClient implements AutoCloseable {
    private final OkHttpClient client;
    private final HttpUrl teamscaleServerUrl;
    private final String user;
    private final String accessKey;
    private final String project;
    Map<String, String> timestampRevisionCache = new HashMap<>();

    public TeamscaleClient(OkHttpClient client, HttpUrl teamscaleServerUrl, String user, String accessKey, String project) {
        this.client = client;
        this.teamscaleServerUrl = teamscaleServerUrl;
        this.user = user;
        this.accessKey = accessKey;
        this.project = project;
    }

    /**
     * @return a pair with added findings (first) and findings in changed code (second) received via the findings-churn api for a single commit ({@code api/projects/{project}/finding-churn/list}).
     * @throws HttpRedirectException   if a redirect is encountered
     * @throws HttpStatusCodeException if an HTTP error code was returned by Teamscale
     * @throws ParserException         if there was an error parsing Teamscale's response
     */
    public Pair<List<Finding>, List<Finding>> fetchFindingsUsingCommitDetails(String branchAndTimestamp) throws IOException, HttpRedirectException, HttpStatusCodeException, ParserException {
        HttpUrl.Builder builder =
                teamscaleServerUrl.newBuilder()
                        .addPathSegments("api/projects")
                        .addPathSegment(project)
                        .addPathSegments("finding-churn/list")
                        .addQueryParameter("t", branchAndTimestamp);
        HttpUrl url = builder.build();
        Request request = createAuthenticatedGetRequest(url);
        String response = sendRequest(request);
        return parseFindingResponse(response);
    }

    /**
     * @return a pair with added findings (first) and findings in changed code (second) received via the linear delta endpoint ({@code /api/projects/{project}/findings/delta}).
     * @throws HttpRedirectException   if a redirect is encountered
     * @throws HttpStatusCodeException if an HTTP error code was returned by Teamscale
     * @throws ParserException         if there was an error parsing Teamscale's response
     */
    public Pair<List<Finding>, List<Finding>> fetchFindingsUsingLinearDelta(String startBranchAndTimestamp, String endBranchAndTimestamp) throws IOException, HttpRedirectException, HttpStatusCodeException, ParserException {
        HttpUrl.Builder builder =
                teamscaleServerUrl.newBuilder().addPathSegments("api/projects").addPathSegment(project)
                        .addPathSegments("findings/delta")
                        .addQueryParameter("t2", endBranchAndTimestamp)
                        .addQueryParameter("t1", startBranchAndTimestamp)
                        .addQueryParameter("uniform-path", "");

        HttpUrl url = builder.build();
        Request request = createAuthenticatedGetRequest(url);
        String response = sendRequest(request);
        return parseFindingResponse(response);
    }

    /**
     * @return a pair with added findings (first) and findings in changed code (second) received via the branch merge delta endpoint ({@code /api/projects/{project}/merge-requests/findings-churn}).
     * @throws HttpRedirectException   if a redirect is encountered
     * @throws HttpStatusCodeException if an HTTP error code was returned by Teamscale
     * @throws ParserException         if there was an error parsing Teamscale's response
     */
    public Pair<List<Finding>, List<Finding>> fetchFindingsUsingBranchMergeDelta(String sourceBranchAndTimestamp, String targetBranchAndTimestamp) throws IOException, HttpRedirectException, HttpStatusCodeException, ParserException {

        HttpUrl.Builder builder =
                teamscaleServerUrl.newBuilder()
                        .addPathSegments("api/projects")
                        .addPathSegment(project)
                        .addPathSegments("merge-requests/finding-churn")
                        .addQueryParameter("source", sourceBranchAndTimestamp)
                        .addQueryParameter("target", targetBranchAndTimestamp);

        HttpUrl url = builder.build();
        Request request = createAuthenticatedGetRequest(url);
        String response = sendRequest(request);
        return parseFindingResponse(response);
    }

    /**
     * @return a list of metric violations received from the metric assessment endpoint ({@code api/projects/{project}/metric-assessments}).
     * @throws HttpRedirectException   if a redirect is encountered
     * @throws HttpStatusCodeException if an HTTP error code was returned by Teamscale
     * @throws ParserException         if there was an error parsing Teamscale's response
     */
    public List<MetricViolation> fetchMetricAssessments(String branchAndTimestamp, String thresholdConfig) throws IOException, HttpRedirectException, HttpStatusCodeException, ParserException {
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
        String response = sendRequest(request);
        return parseMetricResponse(response);
    }

    /**
     * @return {@code branch:timestamp} for the given revision received via the {@code /api/projects/{project}/revision/{revision}/commits} endpoint.
     * @throws HttpRedirectException             if a redirect is encountered
     * @throws HttpStatusCodeException           if an HTTP error code was returned by Teamscale
     * @throws CommitCouldNotBeResolvedException if an error happened during parsing of the responses
     * @throws TooManyCommitsException           If more than one commit was found
     */
    public String fetchTimestampForRevision(String revision) throws IOException, TooManyCommitsException, HttpRedirectException, HttpStatusCodeException, CommitCouldNotBeResolvedException {
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
        String commitDescriptorsJson = sendRequest(request);
        long braceCount = commitDescriptorsJson.chars().filter(c -> c == '{').count();
        if (braceCount == 0) {
            throw new CommitCouldNotBeResolvedException(revision);
        }
        if (braceCount > 1) {
            throw new TooManyCommitsException(revision, commitDescriptorsJson);
        }
        String timestamp = extractTimestamp(commitDescriptorsJson);
        String branchName = extractBranchname(commitDescriptorsJson);

        if (timestamp == null || branchName == null) {
            throw new CommitCouldNotBeResolvedException(revision);
        }

        String branchWithTimestamp = branchName + ":" + timestamp;
        timestampRevisionCache.put(revision, branchWithTimestamp);
        return branchWithTimestamp;
    }

    /**
     * Notifies Teamscale that the repository has been updated. This means that analysis of the new commit will start
     * promptly.
     *
     * @throws RepositoryNotFoundException if the repositoryUrl cannot be found in Teamscale
     * @throws HttpRedirectException       if a redirect is encountered
     * @throws HttpStatusCodeException     if an HTTP error code was returned by Teamscale
     */
    public void triggerCommitHookEvent(String remoteRepositoryUrl) throws IOException, HttpRedirectException, HttpStatusCodeException, RepositoryNotFoundException {
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
        sendRequest(request);
    }

    /**
     * @return whether the analysis has reached the given timestamp on the given branch already
     * @throws HttpRedirectException   if a redirect is encountered
     * @throws HttpStatusCodeException if an HTTP error code was returned by Teamscale
     */
    public boolean isTeamscaleAnalysisFinished(String branchAndTimestampToWaitFor) throws IOException, HttpRedirectException, HttpStatusCodeException {
        String[] branchAndTimestamp = branchAndTimestampToWaitFor.split(":", 2);
        String branch = branchAndTimestamp[0];
        long timestamp = Long.parseLong(branchAndTimestamp[1]);
        return isAnalysisFinished(branch, timestamp);
    }

    private boolean isAnalysisFinished(String branch, long timestamp) throws IOException, HttpRedirectException, HttpStatusCodeException {
        HttpUrl.Builder builder =
                teamscaleServerUrl.newBuilder()
                        .addPathSegments("api/projects")
                        .addPathSegment(project)
                        .addPathSegment("branch-analysis-state")
                        .addPathSegment(branch);
        HttpUrl url = builder.build();
        Request request = createAuthenticatedGetRequest(url);
        String analysisStateJson = sendRequest(request);
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
            throw new ParserException("Could not parse findings JSON response:\n" + response + "\n\nPlease contact CQSE with an error report.", e);
        }
        return result;
    }

    private List<MetricViolation> parseMetricResponse(String response) throws ParserException {
        List<MetricViolation> result = new ArrayList<>();
        try {
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
        } catch (ClassCastException | PathNotFoundException e) {
            throw new ParserException("Could not parse metrics JSON response:\n" + response + "\n\nPlease contact CQSE with an error report.", e);
        }
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
        return null;
    }

    private String extractBranchname(String commitDescriptorsJson) {
        Pattern pattern = Pattern.compile("\"branchName\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(commitDescriptorsJson);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private String sendRequest(Request request) throws IOException, HttpRedirectException, HttpStatusCodeException {
        try (Response response = client.newCall(request).execute()) {
            handleErrors(response);
            return readBodySafe(response);
        }
    }

    private Request createAuthenticatedGetRequest(HttpUrl url) {
        return new Request.Builder()
                .header("Authorization", Credentials.basic(user, accessKey))
                .url(url)
                .get()
                .build();
    }

    public static String readBodySafe(Response response) {
        try {
            ResponseBody body = response.body();
            if (body == null) {
                return "<no response body>";
            }
            return body.string();
        } catch (IOException e) {
            return "Failed to read response body: " + e.getMessage();
        }
    }

    private void handleErrors(Response response) throws HttpRedirectException, HttpStatusCodeException {
        if (response.isRedirect()) {
            String location = response.header("Location");
            if (location == null) {
                location = "<server did not provide a location header>";
            }
            throw new HttpRedirectException(location);
        }
        if (!response.isSuccessful()) {
            throw new HttpStatusCodeException(response.code(), response.toString());
        }
    }

    private String determineRemoteRepositoryUrl(String remoteRepositoryUrl) throws RepositoryNotFoundException {
        String finalRemoteRepositoryUrl = remoteRepositoryUrl;
        List<Supplier<String>> repoUrlDetectionStrategies =
                List.of(() -> finalRemoteRepositoryUrl, GitChecker::findRepoUrl, SvnChecker::findRepoUrl);
        Optional<String> optionalUrl =
                repoUrlDetectionStrategies.stream().map(Supplier::get).filter(Objects::nonNull).findFirst();
        if (!optionalUrl.isPresent()) {
            throw new RepositoryNotFoundException();
        }
        remoteRepositoryUrl = optionalUrl.get();
        return remoteRepositoryUrl;
    }

    @Override
    public void close() {
        client.dispatcher().executorService().shutdownNow();
        client.connectionPool().evictAll();

    }
}
