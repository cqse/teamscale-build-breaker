package com.teamscale.buildbreaker.teamscale_client;

import com.teamscale.buildbreaker.evaluation.Finding;
import com.teamscale.buildbreaker.evaluation.MetricViolation;
import com.teamscale.buildbreaker.evaluation.ProblemCategory;
import com.teamscale.buildbreaker.teamscale_client.exceptions.CommitCouldNotBeResolvedException;
import com.teamscale.buildbreaker.teamscale_client.exceptions.HttpRedirectException;
import com.teamscale.buildbreaker.teamscale_client.exceptions.HttpStatusCodeException;
import com.teamscale.buildbreaker.teamscale_client.exceptions.ParserException;
import com.teamscale.buildbreaker.teamscale_client.exceptions.TooManyCommitsException;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.conqat.lib.commons.collections.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TeamscaleClientTest {

    private MockWebServer server;
    private TeamscaleClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        HttpUrl baseUrl = server.url("/");
        client = new TeamscaleClient(new OkHttpClient(), baseUrl, "user", "key", "test-project");
    }

    @AfterEach
    void tearDown() throws Exception {
        client.close();
        server.shutdown();
    }

    private static String findingJson(String id, String groupName, String categoryName,
                                      String message, String uniformPath, String assessment) {
        return String.format(
                "{\"id\":\"%s\",\"groupName\":\"%s\",\"categoryName\":\"%s\","
                        + "\"message\":\"%s\",\"location\":{\"uniformPath\":\"%s\"},\"assessment\":\"%s\"}",
                id, groupName, categoryName, message, uniformPath, assessment
        );
    }

    private static String commitFindingsResponse(String findingsInAddedCode, String findingsInChangedCode) {
        return "{\"addedFindings\":[" + findingsInAddedCode
                + "],\"findingsInChangedCode\":[" + findingsInChangedCode + "]}";
    }

    private static String deltaFindingsResponse(String findingsInAddedCode, String findingsInChangedCode) {
        return "{\"addedFindings\":{\"findings\":[" + findingsInAddedCode
                + "]},\"findingsInChangedCode\":{\"findings\":[" + findingsInChangedCode + "]}}";
    }

    private void enqueueJsonResponse(String body) {
        server.enqueue(new MockResponse()
                .setBody(body)
                .addHeader("Content-Type", "application/json"));
    }

    @Nested
    class FetchFindingsUsingCommitDetails {

        @Test
        void buildsCorrectRequest() throws Exception {
            enqueueJsonResponse(commitFindingsResponse("", ""));

            client.fetchFindingsUsingCommitDetails("main:1234567890", "");

            RecordedRequest request = server.takeRequest();
            assertThat(request.getPath())
                    .isEqualTo("/api/v9.2/projects/test-project/finding-churn/list?t=main%3A1234567890");
            assertThat(request.getMethod()).isEqualTo("GET");
            assertThat(request.getHeader("Authorization")).startsWith("Basic ");
        }

        @Test
        void parsesAddedFindings() throws Exception {
            String finding = findingJson("f1", "Code Smells", "Unused", "Unused variable",
                    "src/Main.java", "YELLOW");
            enqueueJsonResponse(commitFindingsResponse(finding, ""));

            Pair<List<Finding>, List<Finding>> result =
                    client.fetchFindingsUsingCommitDetails("main:123", "");

            assertThat(result.getFirst()).hasSize(1);
            Finding parsed = result.getFirst().get(0);
            assertThat(parsed.id).isEqualTo("f1");
            assertThat(parsed.group).isEqualTo("Code Smells");
            assertThat(parsed.category).isEqualTo("Unused");
            assertThat(parsed.message).isEqualTo("Unused variable");
            assertThat(parsed.uniformPath).isEqualTo("src/Main.java");
            assertThat(parsed.assessment).isEqualTo(ProblemCategory.WARNING);
        }

        @Test
        void parsesFindingsInChangedCode() throws Exception {
            String finding = findingJson("f2", "Bugs", "NPE", "Possible NPE",
                    "src/Service.java", "RED");
            enqueueJsonResponse(commitFindingsResponse("", finding));

            Pair<List<Finding>, List<Finding>> result =
                    client.fetchFindingsUsingCommitDetails("main:123", "");

            assertThat(result.getSecond()).hasSize(1);
            assertThat(result.getSecond().get(0).id).isEqualTo("f2");
            assertThat(result.getSecond().get(0).assessment).isEqualTo(ProblemCategory.ERROR);
        }

        @Test
        void filtersFindingsByUniformPathPrefix() throws Exception {
            String matching = findingJson("f1", "Smells", "Unused", "msg1",
                    "src/main/Foo.java", "YELLOW");
            String nonMatching = findingJson("f2", "Smells", "Unused", "msg2",
                    "test/FooTest.java", "YELLOW");
            enqueueJsonResponse(commitFindingsResponse(matching + "," + nonMatching, ""));

            Pair<List<Finding>, List<Finding>> result =
                    client.fetchFindingsUsingCommitDetails("main:123", "src/main");

            assertThat(result.getFirst()).hasSize(1);
            assertThat(result.getFirst().get(0).id).isEqualTo("f1");
        }

        @Test
        void handlesEmptyFindings() throws Exception {
            enqueueJsonResponse(commitFindingsResponse("", ""));

            Pair<List<Finding>, List<Finding>> result =
                    client.fetchFindingsUsingCommitDetails("main:123", "");

            assertThat(result.getFirst()).isEmpty();
            assertThat(result.getSecond()).isEmpty();
        }

    }

    @Nested
    class FetchFindingsUsingLinearDelta {

        @Test
        void buildsCorrectRequest() throws Exception {
            enqueueJsonResponse(deltaFindingsResponse("", ""));

            client.fetchFindingsUsingLinearDelta("main:100", "main:200", "src/");

            RecordedRequest request = server.takeRequest();
            assertThat(request.getPath()).contains("/api/projects/test-project/findings/delta");
            assertThat(request.getPath()).contains("t1=main%3A100");
            assertThat(request.getPath()).contains("t2=main%3A200");
            assertThat(request.getPath()).contains("uniform-path=src%2F");
        }

        @Test
        void parsesNewFormatResponse() throws Exception {
            String finding = findingJson("f1", "Group", "Cat", "msg", "src/A.java", "RED");
            enqueueJsonResponse(deltaFindingsResponse(finding, ""));

            Pair<List<Finding>, List<Finding>> result =
                    client.fetchFindingsUsingLinearDelta("main:100", "main:200", "");

            assertThat(result.getFirst()).hasSize(1);
            assertThat(result.getFirst().get(0).id).isEqualTo("f1");
            assertThat(result.getFirst().get(0).assessment).isEqualTo(ProblemCategory.ERROR);
        }

        @Test
        void parsesLegacyFormatResponse() throws Exception {
            String finding = findingJson("f1", "Group", "Cat", "msg", "src/A.java", "YELLOW");
            enqueueJsonResponse(commitFindingsResponse(finding, ""));

            Pair<List<Finding>, List<Finding>> result =
                    client.fetchFindingsUsingLinearDelta("main:100", "main:200", "");

            assertThat(result.getFirst()).hasSize(1);
            assertThat(result.getFirst().get(0).id).isEqualTo("f1");
            assertThat(result.getFirst().get(0).assessment).isEqualTo(ProblemCategory.WARNING);
        }

        @Test
        void parsesMultipleFindingsInBothCategories() throws Exception {
            String added1 = findingJson("f1", "G", "C", "m1", "src/A.java", "RED");
            String added2 = findingJson("f2", "G", "C", "m2", "src/B.java", "YELLOW");
            String changed1 = findingJson("f3", "G", "C", "m3", "src/C.java", "RED");
            enqueueJsonResponse(deltaFindingsResponse(added1 + "," + added2, changed1));

            Pair<List<Finding>, List<Finding>> result =
                    client.fetchFindingsUsingLinearDelta("main:100", "main:200", "");

            assertThat(result.getFirst()).hasSize(2);
            assertThat(result.getSecond()).hasSize(1);
        }

        @Test
        void throwsParserExceptionOnInvalidResponse() {
            enqueueJsonResponse("{\"unexpected\":\"format\"}");

            assertThatThrownBy(() ->
                    client.fetchFindingsUsingLinearDelta("main:100", "main:200", ""))
                    .isInstanceOf(ParserException.class);
        }
    }

    @Nested
    class FetchFindingsUsingBranchMergeDelta {

        @Test
        void buildsCorrectRequest() throws Exception {
            enqueueJsonResponse(deltaFindingsResponse("", ""));

            client.fetchFindingsUsingBranchMergeDelta("feature:100", "main:200", "src/");

            RecordedRequest request = server.takeRequest();
            assertThat(request.getPath())
                    .contains("/api/projects/test-project/merge-requests/finding-churn");
            assertThat(request.getPath()).contains("source=feature%3A100");
            assertThat(request.getPath()).contains("target=main%3A200");
            assertThat(request.getPath()).contains("included-paths=src%2F");
        }

        @Test
        void omitsIncludedPathsWhenUniformPathIsEmpty() throws Exception {
            enqueueJsonResponse(deltaFindingsResponse("", ""));

            client.fetchFindingsUsingBranchMergeDelta("feature:100", "main:200", "");

            RecordedRequest request = server.takeRequest();
            assertThat(request.getPath()).doesNotContain("included-paths");
        }

        @Test
        void parsesNewFormatResponse() throws Exception {
            String addedFinding = findingJson("f1", "Group", "Cat", "msg1", "src/A.java", "RED");
            String changedFinding = findingJson("f2", "Group", "Cat", "msg2", "src/B.java", "YELLOW");
            enqueueJsonResponse(deltaFindingsResponse(addedFinding, changedFinding));

            Pair<List<Finding>, List<Finding>> result =
                    client.fetchFindingsUsingBranchMergeDelta("feature:100", "main:200", "");

            assertThat(result.getFirst()).hasSize(1);
            assertThat(result.getFirst().get(0).assessment).isEqualTo(ProblemCategory.ERROR);
            assertThat(result.getSecond()).hasSize(1);
            assertThat(result.getSecond().get(0).assessment).isEqualTo(ProblemCategory.WARNING);
        }

        @Test
        void parsesLegacyFormatResponse() throws Exception {
            String finding = findingJson("f1", "Group", "Cat", "msg", "src/A.java", "YELLOW");
            enqueueJsonResponse(commitFindingsResponse(finding, ""));

            Pair<List<Finding>, List<Finding>> result =
                    client.fetchFindingsUsingBranchMergeDelta("feature:100", "main:200", "");

            assertThat(result.getFirst()).hasSize(1);
            assertThat(result.getFirst().get(0).id).isEqualTo("f1");
        }
    }

    @Nested
    class FetchMetricAssessments {

        @Test
        void buildsCorrectRequest() throws Exception {
            enqueueJsonResponse("[]");

            client.fetchMetricAssessments("main:123", "default", "src/");

            RecordedRequest request = server.takeRequest();
            assertThat(request.getPath()).contains("/api/projects/test-project/metric-assessments");
            assertThat(request.getPath()).contains("t=main%3A123");
            assertThat(request.getPath()).contains("configuration-name=default");
            assertThat(request.getPath()).contains("uniform-path=src%2F");
        }

        @Test
        void parsesPreTS20249Format() throws Exception {
            String response = "[{\"metrics\":{\"m1\":{"
                    + "\"displayName\":\"Clone Coverage\","
                    + "\"rating\":\"YELLOW\","
                    + "\"formattedTextValue\":\"15.3%\","
                    + "\"metricThresholds\":{\"thresholdYellow\":\"10\",\"thresholdRed\":\"20\"}"
                    + "}}}]";
            enqueueJsonResponse(response);

            List<MetricViolation> result =
                    client.fetchMetricAssessments("main:123", "config", "");

            assertThat(result).hasSize(1);
            MetricViolation violation = result.get(0);
            assertThat(violation.displayName).isEqualTo("Clone Coverage");
            assertThat(violation.yellowThreshold).isEqualTo("10");
            assertThat(violation.redThreshold).isEqualTo("20");
            assertThat(violation.formattedTextValue).isEqualTo("15.3%");
            assertThat(violation.rating).isEqualTo(ProblemCategory.WARNING);
        }

        @Test
        void skipsGreenMetricsInPreTS20249Format() throws Exception {
            String response = "[{\"metrics\":{"
                    + "\"m1\":{\"displayName\":\"Good Metric\",\"rating\":\"GREEN\","
                    + "\"formattedTextValue\":\"0%\","
                    + "\"metricThresholds\":{\"thresholdYellow\":\"10\",\"thresholdRed\":\"20\"}},"
                    + "\"m2\":{\"displayName\":\"Bad Metric\",\"rating\":\"RED\","
                    + "\"formattedTextValue\":\"50%\","
                    + "\"metricThresholds\":{\"thresholdYellow\":\"10\",\"thresholdRed\":\"20\"}}"
                    + "}}]";
            enqueueJsonResponse(response);

            List<MetricViolation> result =
                    client.fetchMetricAssessments("main:123", "config", "");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).displayName).isEqualTo("Bad Metric");
        }

        @Test
        void parsesCurrentFormatNumericValueType() throws Exception {
            String response = "[{\"metrics\":{\"m1\":{"
                    + "\"displayName\":\"Method Length\","
                    + "\"rating\":\"YELLOW\","
                    + "\"value\":42.5,"
                    + "\"schemaEntry\":{\"valueType\":\"NUMERIC\"},"
                    + "\"metricThresholds\":{\"thresholdYellow\":\"30\",\"thresholdRed\":\"50\"}"
                    + "}}}]";
            enqueueJsonResponse(response);

            List<MetricViolation> result =
                    client.fetchMetricAssessments("main:123", "config", "");

            assertThat(result).hasSize(1);
            MetricViolation violation = result.get(0);
            assertThat(violation.displayName).isEqualTo("Method Length");
            assertThat(violation.formattedTextValue).isEqualTo("42.5000");
            assertThat(violation.yellowThreshold).isEqualTo("30");
            assertThat(violation.redThreshold).isEqualTo("50");
            assertThat(violation.rating).isEqualTo(ProblemCategory.WARNING);
        }

        @Test
        void parsesCurrentFormatAssessmentValueType() throws Exception {
            String response = "[{\"metrics\":{\"m1\":{"
                    + "\"displayName\":\"Assessment Metric\","
                    + "\"rating\":\"RED\","
                    + "\"value\":{\"mapping\":[10,5,3]},"
                    + "\"schemaEntry\":{\"valueType\":\"ASSESSMENT\"},"
                    + "\"metricThresholds\":{\"thresholdYellow\":null,\"thresholdRed\":null}"
                    + "}}}]";
            enqueueJsonResponse(response);

            List<MetricViolation> result =
                    client.fetchMetricAssessments("main:123", "config", "");

            assertThat(result).hasSize(1);
            MetricViolation violation = result.get(0);
            assertThat(violation.displayName).isEqualTo("Assessment Metric");
            assertThat(violation.rating).isEqualTo(ProblemCategory.ERROR);
            assertThat(violation.yellowThreshold).isEmpty();
            assertThat(violation.redThreshold).isEmpty();
            assertThat(violation.formattedTextValue).isEqualTo("[Red: 55.6%, Yellow: 16.7%, Green: 0.0%]");
        }

        @Test
        void parsesCurrentFormatOtherValueType() throws Exception {
            String response = "[{\"metrics\":{\"m1\":{"
                    + "\"displayName\":\"String Metric\","
                    + "\"rating\":\"YELLOW\","
                    + "\"value\":\"some-text\","
                    + "\"schemaEntry\":{\"valueType\":\"STRING\"},"
                    + "\"metricThresholds\":{\"thresholdYellow\":\"threshold\",\"thresholdRed\":null}"
                    + "}}}]";
            enqueueJsonResponse(response);

            List<MetricViolation> result =
                    client.fetchMetricAssessments("main:123", "config", "");

            assertThat(result).hasSize(1);
            MetricViolation metricViolation = result.get(0);
            assertThat(metricViolation.displayName).isEqualTo("String Metric");
            assertThat(metricViolation.formattedTextValue).isEqualTo("some-text");
            assertThat(metricViolation.yellowThreshold).isEqualTo("threshold");
            assertThat(metricViolation.redThreshold).isEmpty();
            assertThat(metricViolation.rating).isEqualTo(ProblemCategory.WARNING);
        }

        @Test
        void skipsGreenMetricsInCurrentFormat() throws Exception {
            String response = "[{\"metrics\":{\"m1\":{"
                    + "\"displayName\":\"Good Metric\","
                    + "\"rating\":\"GREEN\","
                    + "\"value\":1.0,"
                    + "\"schemaEntry\":{\"valueType\":\"NUMERIC\"},"
                    + "\"metricThresholds\":{\"thresholdYellow\":\"10\",\"thresholdRed\":\"20\"}"
                    + "}}}]";
            enqueueJsonResponse(response);

            List<MetricViolation> result =
                    client.fetchMetricAssessments("main:123", "config", "");

            assertThat(result).isEmpty();
        }

        @Test
        void returnsEmptyListWhenNoMetrics() throws Exception {
            enqueueJsonResponse("[]");

            List<MetricViolation> result =
                    client.fetchMetricAssessments("main:123", "config", "");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    class FetchTimestampForRevision {

        @Test
        void buildsCorrectRequest() throws Exception {
            enqueueJsonResponse("[{\"branchName\":\"main\",\"timestamp\":1707123456000}]");

            client.fetchTimestampForRevision("abc123");

            RecordedRequest request = server.takeRequest();
            assertThat(request.getPath())
                    .isEqualTo("/api/v9.2/projects/test-project/revision/abc123/commits");
        }

        @Test
        void returnsBranchAndTimestamp() throws Exception {
            enqueueJsonResponse("[{\"branchName\":\"main\",\"timestamp\":1707123456000}]");

            String result = client.fetchTimestampForRevision("abc123");

            assertThat(result).isEqualTo("main:1707123456000");
        }

        @Test
        void handlesBranchNameWithSlashes() throws Exception {
            enqueueJsonResponse(
                    "[{\"branchName\":\"feature/my-branch\",\"timestamp\":1707123456000}]");

            String result = client.fetchTimestampForRevision("abc123");

            assertThat(result).isEqualTo("feature/my-branch:1707123456000");
        }

        @Test
        void cachesResultForSameRevision() throws Exception {
            enqueueJsonResponse("[{\"branchName\":\"main\",\"timestamp\":1707123456000}]");

            String first = client.fetchTimestampForRevision("abc123");
            String second = client.fetchTimestampForRevision("abc123");

            assertThat(first).isEqualTo(second);
            assertThat(server.getRequestCount()).isEqualTo(1);
        }

        @Test
        void doesNotUseCacheForDifferentRevisions() throws Exception {
            enqueueJsonResponse("[{\"branchName\":\"main\",\"timestamp\":100}]");
            enqueueJsonResponse("[{\"branchName\":\"dev\",\"timestamp\":200}]");

            String first = client.fetchTimestampForRevision("rev1");
            String second = client.fetchTimestampForRevision("rev2");

            assertThat(first).isEqualTo("main:100");
            assertThat(second).isEqualTo("dev:200");
            assertThat(server.getRequestCount()).isEqualTo(2);
        }

        @Test
        void throwsCommitCouldNotBeResolvedForEmptyResponse() {
            enqueueJsonResponse("[]");

            assertThatThrownBy(() -> client.fetchTimestampForRevision("unknown"))
                    .isInstanceOf(CommitCouldNotBeResolvedException.class);
        }

        @Test
        void throwsTooManyCommitsForMultipleResults() {
            enqueueJsonResponse(
                    "[{\"branchName\":\"main\",\"timestamp\":100},"
                            + "{\"branchName\":\"dev\",\"timestamp\":200}]");

            assertThatThrownBy(() -> client.fetchTimestampForRevision("ambiguous"))
                    .isInstanceOf(TooManyCommitsException.class);
        }

        @Test
        void throwsCommitCouldNotBeResolvedWhenTimestampMissing() {
            enqueueJsonResponse("[{\"branchName\":\"main\"}]");

            assertThatThrownBy(() -> client.fetchTimestampForRevision("bad"))
                    .isInstanceOf(CommitCouldNotBeResolvedException.class);
        }

        @Test
        void throwsCommitCouldNotBeResolvedWhenBranchNameMissing() {
            enqueueJsonResponse("[{\"timestamp\":1707123456000}]");

            assertThatThrownBy(() -> client.fetchTimestampForRevision("bad"))
                    .isInstanceOf(CommitCouldNotBeResolvedException.class);
        }
    }

    @Nested
    class FetchAnalysisState {

        @Test
        void buildsCorrectRequest() throws Exception {
            enqueueJsonResponse("{\"timestamp\":1707000000000,\"state\":\"LIVE_ANALYSIS\"}");

            client.fetchAnalysisState("feature/my-branch");

            assertThat(server.takeRequest().getPath())
                    .isEqualTo("/api/projects/test-project/branch-analysis-state/feature%2Fmy-branch");
        }

        @Test
        void parsesAllFields() throws Exception {
            enqueueJsonResponse(
                    "{\"timestamp\":1707000000000,\"state\":\"ROLLBACK_ANALYSIS\","
                            + "\"rollbackId\":\"abc-123\"}");

            AnalysisState state = client.fetchAnalysisState("main");

            assertThat(state.timestamp).isEqualTo(1707000000000L);
            assertThat(state.state).isEqualTo("ROLLBACK_ANALYSIS");
            assertThat(state.rollbackId).isEqualTo("abc-123");
        }

        @Test
        void handlesAbsentRollbackId() throws Exception {
            enqueueJsonResponse("{\"timestamp\":1707123456000,\"state\":\"LIVE_ANALYSIS\"}");

            AnalysisState state = client.fetchAnalysisState("main");

            assertThat(state.rollbackId).isNull();
        }

        @Test
        void handlesNullRollbackId() throws Exception {
            enqueueJsonResponse(
                    "{\"timestamp\":1707123456000,\"state\":\"HISTORY_ANALYSIS\","
                            + "\"rollbackId\":null}");

            AnalysisState state = client.fetchAnalysisState("main");

            assertThat(state.rollbackId).isNull();
        }

        @Test
        void handlesLargeTimestampExceedingIntegerRange() throws Exception {
            long largeTimestamp = (long) Integer.MAX_VALUE + 1000L;
            enqueueJsonResponse(
                    "{\"timestamp\":" + largeTimestamp + ",\"state\":\"LIVE_ANALYSIS\"}");

            AnalysisState state = client.fetchAnalysisState("main");

            assertThat(state.timestamp).isEqualTo(largeTimestamp);
        }
    }

    @Nested
    class TriggerCommitHookEvent {

        @Test
        void sendsPostRequestToCorrectEndpoint() throws Exception {
            server.enqueue(new MockResponse().setResponseCode(200));

            client.triggerCommitHookEvent("https://github.com/org/repo.git");

            RecordedRequest request = server.takeRequest();
            assertThat(request.getPath()).contains("/api/post-commit-hook");
            assertThat(request.getPath())
                    .contains("repository=https%3A%2F%2Fgithub.com%2Forg%2Frepo.git");
            assertThat(request.getMethod()).isEqualTo("POST");
        }

        @Test
        void includesAuthorizationHeader() throws Exception {
            server.enqueue(new MockResponse().setResponseCode(200));

            client.triggerCommitHookEvent("https://github.com/org/repo.git");

            RecordedRequest request = server.takeRequest();
            assertThat(request.getHeader("Authorization")).startsWith("Basic ");
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        void throwsHttpStatusCodeExceptionOnServerError() {
            server.enqueue(new MockResponse()
                    .setResponseCode(500)
                    .setBody("Internal Server Error"));

            assertThatThrownBy(() -> client.fetchAnalysisState("main"))
                    .isInstanceOf(HttpStatusCodeException.class)
                    .satisfies(e -> {
                        HttpStatusCodeException ex = (HttpStatusCodeException) e;
                        assertThat(ex.getStatusCode()).isEqualTo(500);
                    });
        }

        @Test
        void throwsHttpStatusCodeExceptionOnNotFound() {
            server.enqueue(new MockResponse()
                    .setResponseCode(404)
                    .setBody("Not Found"));

            assertThatThrownBy(() ->
                    client.fetchFindingsUsingCommitDetails("main:123", ""))
                    .isInstanceOf(HttpStatusCodeException.class)
                    .satisfies(e -> {
                        HttpStatusCodeException ex = (HttpStatusCodeException) e;
                        assertThat(ex.getStatusCode()).isEqualTo(404);
                    });
        }

        @Test
        void throwsHttpStatusCodeExceptionOnUnauthorized() {
            server.enqueue(new MockResponse()
                    .setResponseCode(401)
                    .setBody("Unauthorized"));

            assertThatThrownBy(() ->
                    client.fetchMetricAssessments("main:123", "config", ""))
                    .isInstanceOf(HttpStatusCodeException.class)
                    .satisfies(e -> {
                        HttpStatusCodeException ex = (HttpStatusCodeException) e;
                        assertThat(ex.getStatusCode()).isEqualTo(401);
                    });
        }

        @Test
        void throwsHttpRedirectException() {
            OkHttpClient nonRedirectClient = new OkHttpClient.Builder()
                    .followRedirects(false)
                    .build();

            try (TeamscaleClient nonRedirectTeamscaleClient = new TeamscaleClient(
                    nonRedirectClient, server.url("/"), "user", "key", "test-project")) {
                server.enqueue(new MockResponse()
                        .setResponseCode(302)
                        .addHeader("Location", "https://other-server.example.com/"));
                assertThatThrownBy(() -> nonRedirectTeamscaleClient.fetchAnalysisState("main"))
                        .isInstanceOf(HttpRedirectException.class)
                        .satisfies(e -> {
                            HttpRedirectException ex = (HttpRedirectException) e;
                            assertThat(ex.getRedirectLocation())
                                    .isEqualTo("https://other-server.example.com/");
                        });
            }
        }

        @Test
        void httpStatusCodeExceptionContainsResponseBody() {
            server.enqueue(new MockResponse()
                    .setResponseCode(400)
                    .setBody("Bad Request: missing parameter"));

            assertThatThrownBy(() -> client.fetchAnalysisState("main"))
                    .isInstanceOf(HttpStatusCodeException.class)
                    .satisfies(e -> {
                        HttpStatusCodeException ex = (HttpStatusCodeException) e;
                        assertThat(ex.getResponseBody()).isEqualTo("Bad Request: missing parameter");
                    });
        }
    }
}
