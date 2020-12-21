package com.cqse.buildbreaker;

import com.cqse.buildbreaker.autodetect_revision.EnvironmentVariableChecker;
import com.cqse.buildbreaker.autodetect_revision.GitChecker;
import com.cqse.buildbreaker.autodetect_revision.SvnChecker;
import com.cqse.buildbreaker.data.CommitDescriptor;
import com.cqse.buildbreaker.exceptions.CommitCouldNotBeResolvedException;
import com.cqse.buildbreaker.exceptions.KeystoreException;
import com.cqse.buildbreaker.exceptions.SslConnectionFailureException;
import com.cqse.buildbreaker.exceptions.TeamscaleFeedbackInternalException;
import com.google.gson.Gson;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.conqat.lib.commons.string.StringUtils;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.IExitCodeExceptionMapper;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.Spec;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.Callable;

@Command(name = "teamscale-feedback", mixinStandardHelpOptions = true, version = "teamscale-feedback 0.1",
        description = "Queries a Teamscale server for analysis results and emits a corresponding status code.",
        footer = "\nBy default, teamscale-feedback tries to automatically detect the code commit" +
                " for which to obtain feedback from environment variables or a Git or SVN checkout in the" +
                " current working directory. This feature" +
                " supports many common CI tools like Jenkins, GitLab, GitHub Actions, Travis CI etc." +
                " If automatic detection fails, you can manually specify either a commit via --commit," +
                " a branch and timestamp via --branch-and-timestamp or you can upload to the latest" +
                " commit on a branch via --branch-and-timestamp my-branch:HEAD.")
public class TeamscaleFeedback implements Callable<Integer> {

    /** The command spec models how this executable can be called. It is automatically injected by PicoCli. */
    @Spec
    CommandSpec spec;

    private HttpUrl teamscaleServerUrl;

    @Option(names = {"-p", "--project"}, required = true, description = "The project ID or alias (NOT the project name!) relevant for the analysis.")
    private String project;

    @Option(names = {"-r", "--threshold-config"}, required = true, description = "The name of the threshold config that should be used.")
    private String thresholdConfig;

    @Option(names = {"-y", "--fail-on-yellow"}, description = "Fail on yellow findings.")
    private boolean failOnYellow;

    @Option(names = {"-m", "--fail-on-modified"}, description = "Fail on findings in modified code (not just findings in new code).")
    private boolean failOnModified;

    @Option(names = {"-l", "--login"}, description = "If the user should perform a login first.")
    private boolean shouldLogin;

    private String user;

    private String accessKey;

    private String branchAndTimestamp;

    private String commit;

    private boolean disableSslValidation;

    private String keyStorePath;
    private String keyStorePassword;
    private OkHttpClient client;

    @Option(names = {"-s", "--server"}, paramLabel = "<teamscale-server-url>", required = true, description = "The URL under which the Teamscale server can be reached.")
    public void setTeamscaleServerUrl(String teamscaleServerUrl) {
        this.teamscaleServerUrl = HttpUrl.parse(teamscaleServerUrl);
        if (this.teamscaleServerUrl == null) {
            throw new ParameterException(spec.commandLine(), "The URL you entered is not well-formed: " + teamscaleServerUrl);
        }
    }

    @Option(names = {"-u", "--user"}, required = true, description = "The user that performs the query. Requires VIEW permission on the queried project.")
    public void setUser(String user) {
        this.user = user;
    }

    @Option(names = {"-a", "--accesskey"}, paramLabel = "<accesskey>", required = true, description = "The IDE access key of the given user. Can be retrieved in Teamscale under Admin > Users.")
    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    @Option(names = {"-b", "--branch-and-timestamp"}, paramLabel = "<branch:timestamp>", description = "The branch and Unix Epoch timestamp for which analysis results should be evaluated." +
            " This is typically the branch and commit timestamp of the commit that the current CI pipeline" +
            " is building. The timestamp must be milliseconds since" +
            " 00:00:00 UTC Thursday, 1 January 1970 or the string 'HEAD' to upload to" +
            " the latest revision on that branch." +
            "\nFormat: BRANCH:TIMESTAMP" +
            "\nExample: master:1597845930000" +
            "\nExample: develop:HEAD")
    public void setBranchAndTimestamp(String branchAndTimestamp) {
        validateBranchAndTimestamp(branchAndTimestamp);
        this.branchAndTimestamp = branchAndTimestamp;
    }

    @Option(names = "--disable-ssl-validation", description = "By default, SSL certificates are validated against the configured KeyStore." +
            " This flag disables validation which makes using this tool with self-signed certificates easier.")
    public void setDisableSslValidation(boolean disableSslValidation) {
        if (!StringUtils.isEmpty(keyStorePath)) {
            throw new ParameterException(spec.commandLine(), "Please only use either --trusted-keystore or --disable-ssl-validation, but not both at the same time");
        }
        this.disableSslValidation = disableSslValidation;
    }

    @Option(names = "--trusted-keystore", paramLabel = "<keystore-path;password>", description = "A Java KeyStore file and its corresponding password. The KeyStore contains" +
            " additional certificates that should be trusted when performing SSL requests." +
            " Separate the path from the password with a semicolon, e.g:" +
            "\n/path/to/keystore.jks;PASSWORD" +
            "\nThe path to the KeyStore must not contain a semicolon. Cannot be used in conjunction with --disable-ssl-validation.")
    public void setKeyStorePathAndPassword(String keystoreAndPassword) {
        if (disableSslValidation) {
            throw new ParameterException(spec.commandLine(),
                    "Please only use either --trusted-keystore or --disable-ssl-validation, but not both at the same time.");
        }
        String[] keystoreAndPasswordSplit = keystoreAndPassword.split(";", 2);
        this.keyStorePath = keystoreAndPasswordSplit[0];
        if (StringUtils.isEmpty(this.keyStorePath)) {
            throw new ParameterException(spec.commandLine(),
                    "You must supply a valid KeyStore path.");
        }
        this.keyStorePassword = keystoreAndPasswordSplit[1];
    }

    @Option(names = {"-c", "--commit"}, paramLabel = "<commit-revision>", description = "The version control commit revision for which analysis results should be obtained." +
            " This is typically the commit that the current CI pipeline is building." +
            " Can be either a Git SHA1, a SVN revision number or a Team Foundation changeset ID.")
    public void setCommit(String commitRevision) {
        if (branchAndTimestamp != null) {
            throw new ParameterException(spec.commandLine(),
                    "Please only use either --commit or --branch-and-timestamp, but not both at the same time!");
        }
        this.commit = commitRevision;
    }

    public static void main(String... args) {
        // Just let PicoCLI handle everything. Main entry point for PicoCLI is the "call()" method.
        int exitCode = new CommandLine(new TeamscaleFeedback())
                .setExecutionExceptionHandler(new PrintExceptionMessageHandler())
                .setExitCodeExceptionMapper(new ExceptionToExitCodeMapper())
                .execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        client = OkHttpClientUtils.createClient(!disableSslValidation, keyStorePath, keyStorePassword);
        try {
            String metricAssessments = fetchMetricAssessments();
            EvaluationResult metricResult = new MetricsEvaluator().evaluate(metricAssessments);
            System.out.println(metricResult);
            return 0;
        } catch (SSLHandshakeException e) {
            handleSslConnectionFailure(e);
            return -1;
        } finally {
            // we must shut down OkHttp as otherwise it will leave threads running and
            // prevent JVM shutdown
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
        }
    }

    private String fetchMetricAssessments() throws IOException {
        HttpUrl.Builder builder = teamscaleServerUrl.newBuilder()
                .addPathSegments("api/projects")
                .addPathSegment(project)
                .addPathSegments("metric-assessments")
                .addQueryParameter("uniform-path", "")
                .addQueryParameter("configuration-name", thresholdConfig);
        addRevisionOrBranchTimestamp(builder);
        HttpUrl url = builder.build();

        Request request = createAuthenticatedGetRequest(url);

        return sendRequest(url, request);
    }

    private String sendRequest(HttpUrl url, Request request) throws IOException {
        try (Response response = client.newCall(request).execute()) {
            handleErrors(response);
            return readBodySafe(response);
        } catch (UnknownHostException e) {
            fail("The host " + url + " could not be resolved. Please ensure you have no typo and that" +
                    " this host is reachable from this server. " + e.getMessage());
        } catch (ConnectException e) {
            fail("The URL " + url + " refused a connection. Please ensure that you have no typo and that" +
                    " this endpoint is reachable and not blocked by firewalls. " + e.getMessage());
        }
        // Never reached
        return null;
    }

    private Request createAuthenticatedGetRequest(HttpUrl url) {
        return new Request.Builder()
                .header("Authorization", Credentials.basic(user, accessKey))
                .url(url)
                .get()
                .build();
    }

    /**
     * Adds either a revision or t parameter to the given builder, based on the input.
     * <p>
     * We track revision or branch:timestamp for the session as it should be the same for all uploads.
     */
    private void addRevisionOrBranchTimestamp(HttpUrl.Builder builder) throws IOException {
        if (!StringUtils.isEmpty(commit)) {
            builder.addQueryParameter("t", fetchTimestampForRevision(commit));
        } else if (!StringUtils.isEmpty(branchAndTimestamp)) {
            builder.addQueryParameter("t", branchAndTimestamp);
        } else {
            // auto-detect if neither option is given
            String commit = detectCommit();
            if (commit == null) {
                throw new ParameterException(spec.commandLine(), "Failed to automatically detect the commit. Please specify it manually via --commit or --branch-and-timestamp");
            }
            builder.addQueryParameter("t", fetchTimestampForRevision(commit));
        }
    }

    private String fetchTimestampForRevision(String revision) throws IOException {
        HttpUrl.Builder builder = teamscaleServerUrl.newBuilder()
                .addPathSegments("api/projects")
                .addPathSegment(project)
                .addPathSegments("revision")
                .addPathSegment(revision)
                .addPathSegments("commits");
        HttpUrl url = builder.build();
        Request request = createAuthenticatedGetRequest(url);
        String commitDescriptorsJson = sendRequest(url, request);
        CommitDescriptor[] commitDescriptors = new Gson().fromJson(commitDescriptorsJson, CommitDescriptor[].class);
        if (commitDescriptors.length == 0) {
            throw new CommitCouldNotBeResolvedException("Could not resolve revision " + commit + " to a valid commit known to Teamscale (no commits returned)");
        }
        if (commitDescriptors.length > 1) {
            throw new CommitCouldNotBeResolvedException("Could not resolve revision " + this.commit + " to a valid commit known to Teamscale (too many commits returned): " + StringUtils.concat(commitDescriptors, ", "));
        }
        return toServiceCallFormat(commitDescriptors[0]);
    }

    public String toServiceCallFormat(CommitDescriptor commitDescriptor) {
        String branchName = commitDescriptor.getBranchName();
        Long timestamp = commitDescriptor.getTimestamp();
        String result = branchName + ":";
        if ("##no-branch##".contentEquals(Objects.requireNonNull(branchName))) {
            result = StringUtils.EMPTY_STRING;
        }
        if (Objects.requireNonNull(timestamp) == Long.MAX_VALUE) {
            return result + "HEAD";
        }
        return result + timestamp;
    }

    private static final String command = "native-image -cp /Users/j.muller/.m2/repository/com/squareup/okhttp3/okhttp/3.14.2/okhttp-3.14.2.jar:/Users/j.muller/.m2/repository/com/squareup/okio/okio/1.17.2/okio-1.17.2.jar:/Users/j.muller/.m2/repository/com/fasterxml/jackson/core/jackson-databind/2.9.8/jackson-databind-2.9.8.jar:/Users/j.muller/.m2/repository/com/fasterxml/jackson/core/jackson-annotations/2.9.0/jackson-annotations-2.9.0.jar:/Users/j.muller/.m2/repository/com/fasterxml/jackson/core/jackson-core/2.9.8/jackson-core-2.9.8.jar:/Users/j.muller/Desktop/CQSE.nosync/Work/git_repo/MavenProject/okta-graalvm-example/jdk/target/okta-graal-example-jdk-1.0-SNAPSHOT.jar -H:Class=com.okta.examples.jdk.OkHttpExample -H:Name=thresholdEvaluation -H:+AddAllCharsets --no-fallback --enable-http --enable-https";
    private static final String additionalInfo = "in jdk folder, mvn package + command";

    public void handleSslConnectionFailure(SSLHandshakeException e) {
        if (!StringUtils.isEmpty(keyStorePath)) {
            throw new SslConnectionFailureException("Failed to connect via HTTPS to " + teamscaleServerUrl + ": " + e.getMessage() +
                    "\nYou enabled certificate validation and provided a keystore with certificates" +
                    " that should be considered valid. Still, the connection failed." +
                    " Most likely, you did not provide the correct certificates in the keystore" +
                    " or some certificates are missing from it." +
                    "\nPlease also ensure that your Teamscale instance is reachable under " + teamscaleServerUrl +
                    " and that it is configured for HTTPS, not HTTP. E.g. open that URL in your" +
                    " browser and verify that you can connect successfully.");
        } else if (disableSslValidation) {
            throw new SslConnectionFailureException("Failed to connect via HTTPS to " + teamscaleServerUrl + ": " + e.getMessage() +
                    "\nYou enabled certificate validation. Most likely, your certificate" +
                    " is either self-signed or your root CA's certificate is not known to" +
                    " teamscale-upload. Please provide the path to a keystore that contains" +
                    " the necessary public certificates that should be trusted by" +
                    " teamscale-upload via --trusted-keystore. You can create a Java keystore" +
                    " with your certificates as described here:" +
                    " https://docs.teamscale.com/howto/connecting-via-https/#using-self-signed-certificates" +
                    "\nPlease also ensure that your Teamscale instance is reachable under " + teamscaleServerUrl +
                    " and that it is configured for HTTPS, not HTTP. E.g. open that URL in your" +
                    " browser and verify that you can connect successfully.");
        } else {
            throw new SslConnectionFailureException("Failed to connect via HTTPS to " + teamscaleServerUrl + ": " + e.getMessage() +
                    "\nPlease ensure that your Teamscale instance is reachable under " + teamscaleServerUrl +
                    " and that it is configured for HTTPS, not HTTP. E.g. open that URL in your" +
                    " browser and verify that you can connect successfully.");
        }
    }

    private void validateBranchAndTimestamp(String branchAndTimestamp) throws ParameterException {
        if (StringUtils.isEmpty(branchAndTimestamp)) {
            return;
        }

        String[] parts = branchAndTimestamp.split(":", 2);
        if (parts.length == 1) {
            throw new ParameterException(spec.commandLine(), "You specified an invalid branch and timestamp" +
                    " with --branch-and-timestamp: " + branchAndTimestamp + "\nYou must  use the" +
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
                String millisecondDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                        Instant.ofEpochMilli(unixTimestamp).atZone(ZoneOffset.UTC));
                String secondDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                        Instant.ofEpochSecond(unixTimestamp).atZone(ZoneOffset.UTC));
                throw new ParameterException(spec.commandLine(), "You specified an invalid timestamp with" +
                        " --branch-and-timestamp. The timestamp '" + timestampPart + "'" +
                        " is equal to " + millisecondDate + ". This is probably not what" +
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

    private static String detectCommit() {
        String commit = EnvironmentVariableChecker.findCommit();
        if (commit != null) {
            return commit;
        }

        commit = GitChecker.findCommit();
        if (commit != null) {
            return commit;
        }

        return SvnChecker.findRevision();
    }

    private void handleErrors(Response response) {
        if (response.isRedirect()) {
            String location = response.header("Location");
            if (location == null) {
                location = "<server did not provide a location header>";
            }
            fail("You provided an incorrect URL. The server responded with a redirect to " +
                            "'" + location + "'." +
                            " This may e.g. happen if you used HTTP instead of HTTPS." +
                            " Please use the correct URL for Teamscale instead.",
                    response);
        }

        if (response.code() == 401) {
            HttpUrl editUserUrl = teamscaleServerUrl.newBuilder().addPathSegments("admin.html#users").addQueryParameter("action", "edit")
                    .addQueryParameter("username", user).build();
            fail("You provided incorrect credentials." +
                            " Either the user '" + user + "' does not exist in Teamscale" +
                            " or the access key you provided is incorrect." +
                            " Please check both the username and access key in Teamscale under Admin > Users:" +
                            " " + editUserUrl +
                            "\nPlease use the user's access key, not their password.",
                    response);
        }

        if (response.code() == 403) {
            // can't include a URL to the corresponding Teamscale screen since that page does not support aliases
            // and the user may have provided an alias, so we'd be directing them to a red error page in that case
            fail("The user user '" + user + "' is not allowed to upload data to the Teamscale project '" + project + "'." +
                            " Please grant this user the 'Perform External Uploads' permission in Teamscale" +
                            " under Project Configuration > Projects by clicking on the button showing three" +
                            " persons next to project '" + project + "'.",
                    response);
        }

        if (response.code() == 404) {
            HttpUrl projectPerspectiveUrl = teamscaleServerUrl.newBuilder().addPathSegments("project.html").build();
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

    private static String readBodySafe(Response response) {
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

    /**
     * Print error message and exit the program
     */
    public void fail(String message) {
        throw new ParameterException(spec.commandLine(), message);
    }

    /**
     * Print error message and server response, then exit program
     */
    private void fail(String message, Response response) {
        fail("Program execution failed:\n\n" + message + "\n\nTeamscale's response:\n" +
                response.toString() + "\n" + readBodySafe(response));
    }
}

class PrintExceptionMessageHandler implements IExecutionExceptionHandler {
    public int handleExecutionException(Exception ex,
                                        CommandLine cmd,
                                        ParseResult parseResult) {
        // bold red error message
        cmd.getErr().println(cmd.getColorScheme().stackTraceText(ex));

        return cmd.getExitCodeExceptionMapper() != null
                ? cmd.getExitCodeExceptionMapper().getExitCode(ex)
                : cmd.getCommandSpec().exitCodeOnExecutionException();
    }
}

class ExceptionToExitCodeMapper implements IExitCodeExceptionMapper {
    @Override
    public int getExitCode(Throwable t) {
        if (t instanceof SslConnectionFailureException) {
            return -2;
        } else if (t instanceof KeystoreException) {
            return -3;
        } else if (t instanceof TeamscaleFeedbackInternalException) {
            return -4;
        } else if (t instanceof CommitCouldNotBeResolvedException) {
            return -5;
        }
        return -1;
    }
}