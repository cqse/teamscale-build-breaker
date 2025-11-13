# Teamscale Build-Breaker

Queries a Teamscale server for analysis results, evaluates them and emits a corresponding status code. The tool can be
built as a native image using Maven.

## Usage

Run this program giving all required parameters, additionally activating either **--evaluate-findings** or
**--evaluate-thresholds** (or both). The program will contact the given Teamscale server and evaluate the configured
thresholds or findings. If no violations of the configured rules are detected, the program exits with status code 0. In
case a violation is detected, the violations are printed to the command line and the program exits with a non-zero,
positive status code. If an internal error occurs during execution, debugging information is printed to the command line
and the program exits with a non-zero, negative status code.

Use this in your CI pipeline to break the build when a non-zero status code is detected. If you want to keep the build
running in case of internal errors, only break the build on positive status codes.

### Required Parameters

**-p**, **--project**=*&lt;project&gt;*  
The project ID or alias (NOT the project name!) relevant for the analysis.

**-s**, **--server**=*&lt;teamscale-server-url&gt;*  
The URL under which the Teamscale server can be reached.

**-u**, **--user**=*&lt;user&gt;*  
The user that performs the query. Requires VIEW permission on the queried project.

**-a**, **--accesskey**=*&lt;accesskey&gt;*  
The IDE access key of the given user. Can be retrieved in Teamscale under Admin &gt; Users.

### Optional Parameters

**-b**, **--branch-and-timestamp**=*&lt;branch:timestamp&gt;*  
The branch and Unix Epoch timestamp for which analysis results should be evaluated. This is typically the branch and
commit timestamp of the commit that the current CI pipeline is building. The timestamp must be milliseconds since 00:00:
00 UTC Thursday, 1 January 1970.

Format: BRANCH:TIMESTAMP

Example: master:1597845930000

**-c**, **--commit**=*&lt;commit-revision&gt;*  
The version control commit revision for which analysis results should be obtained. This is typically the commit that the
current CI pipeline is building. Can be either a Git SHA1, a SVN revision number or a Team Foundation changeset ID.

**--uniform-path**
Set this option to filter the location of findings and metric assessments.

**-f**, **--evaluate-findings**  
If this option is set, findings introduced with the given commit will be evaluated.

**--fail-on-modified-code-findings**  
Whether to fail on findings in modified code (not just new findings).

**--fail-on-yellow-findings**  
Whether to fail on yellow findings (with exit code 2).

**--target-revision**=*&lt;revision-hash&gt;*  
The revision (hash) to compare with using Teamscale's branch merge delta service. If specified, findings will be
evaluated based on what would happen if the current commit (or the one specified via --commit) would be merged into this
commit. This will
take precedence over --target-branch-and-timestamp.

**--target-branch-and-timestamp**=*&lt;branch:timestamp&gt;*  
The branch and timestamp to compare with using Teamscale's branch merge delta service. If specified, findings will be
evaluated based on what would happen if the current commit (or the one specified via --commit) would be merged into this
commit.
--target-revision will take precedence over this option if provided.

**--base-revision**=*&lt;revision-hash&gt;*  
The base revision (hash) to compare with using Teamscale's linear delta service. The commit needs to be a parent of the
current commit (or the one specified via --commit). If specified, findings of all commits in between the two will be
evaluated. This will take
precedence over --base-branch-and-timestamp.

**--base-branch-and-timestamp**=*&lt;branch:timestamp&gt;*  
The base branch and timestamp to compare with using Teamscale's linear delta service. The commit needs to be a parent of
the current commit (or the one specified via --commit). If specified, findings of all commits in between the two will be
evaluated.
--base-revision will take precedence over this option if provided.

**--fail-on-yellow-metrics**  
Whether to fail on yellow metrics (with exit code 2).

**-h**, **--help**  
Show this help message and exit.

**--insecure**  
By default, SSL certificates are validated against the configured KeyStore. This flag disables validation which makes
using this tool with self-signed certificates easier.

**-o**, **--threshold-config**=*&lt;thresholdConfig&gt;*  
The name of the threshold config that should be used.

**--repository-url**=*&lt;remote-repository-url&gt;*  
The URL of the remote repository where the analyzed commit originated. This is required in case a commit hook event
should be sent to Teamscale for this repository if the repository URL cannot be established from the build environment.

**-t**, **--evaluate-thresholds**  
If this option is set, metrics from a given threshold profile will be evaluated.

**--trusted-keystore**=*&lt;keystore-path;password&gt;*  
A Java KeyStore file and its corresponding password. The KeyStore contains additional certificates that should be
trusted when performing SSL requests. Separate the path from the password with a semicolon, e.g:

/path/to/keystore.jks;PASSWORD

The path to the KeyStore must not contain a semicolon. Cannot be used in conjunction with --disable-ssl-validation.

**-V**, **--version**  
Print version information and exit.

**--wait-for-analysis-timeout**=*&lt;iso-8601-duration&gt;*
The duration this tool will wait for analysis of the given commit to be finished in Teamscale, given in ISO-8601
format (e.g., PT20m for 20 minutes or PT30s for 30 seconds). This is useful when Teamscale starts analyzing at the same
time this tool is called, and analysis is not yet finished. Default value is 20 minutes.

**Exit codes**

- 0: successful evaluation, no violations detected
- 1: errors detected
- 2: warnings detected (when evaluation of warnings is enabled)
- -1, or other negative number: an internal error occurred, please contact the developers

Running the native image only with --help/--version returns the help message/version of the native image.

## Jenkins integration

To use this tool in a Jenkins pipeline, complete the following steps:

1. Place the binary into a directory of your choice on your Jenkins build agents
2. Make sure you have the "Credentials" as well as the "Credentials Binding" Jenkins plugin
   installed (https://plugins.jenkins.io/credentials/, https://plugins.jenkins.io/credentials-binding/)
3. In Teamscale, acquire the Teamscale access key for the user that should connect to the Teamscale instance (as
   explained in https://docs.teamscale.com/glossary/#access-key). The user needs to have `VIEW` permissions on the
   project
4. Enter the user name and access key into the Jenkins credentials store and save it under a fitting ID, e.g. "
   teamscale-credentials"
5. Define a pipeline stage such as the following:

       pipeline {
          agent any

          stages {
              stage ('Teamscale Analysis') {
                  steps {
                      withCredentials([usernamePassword(credentialsId: 'teamscale-credentials', usernameVariable: 'USER', passwordVariable: 'ACCESSKEY')]) {
                          script {
                              def statusCode = sh returnStatus: true, script:'/path/to/teamscale-buildbreaker --user=$USER --accesskey=$ACCESSKEY --project=...'
                              if (statusCode == 0) {
                                  currentBuild.result = 'SUCCESS';
                                  currentBuild.description = 'Teamscale analysis passed successfully';
                              } else if (statusCode == 1) {
                                  currentBuild.result = 'FAILURE';
                                  currentBuild.description = 'Teamscale analysis detected rule violations';
                              } else if (statusCode == 2) {
                                  currentBuild.result = 'FAILURE';
                                  currentBuild.description = 'Teamscale analysis detected warnings';
                              } else if (statusCode < 0) {
                                  currentBuild.result = 'UNSTABLE';
                                  currentBuild.description = 'Could not fetch analysis result from Teamscale (internal error)';
                              } else {
                                  currentBuild.result = 'UNSTABLE';
                                  currentBuild.description = 'Unknown status code ' + statusCode;
                              }
                          }
                      }
                  }
              }
          }
       }

Please adapt the path according to your installation and set the parameters of the call as explained in the "Usage"
section of this document.

On a Jenkins Windows installation, use `bat` instead of `sh` to call the tool. Feel free to adapt the build results and
descriptions according to your needs, e.g. you might break the build instead of setting it to unstable in case of an
internal error of the tool if you like.

## Building the Native Image

**Prerequisites**

- Install graalvm (https://www.graalvm.org/docs/getting-started/)
- Install native-image extension of graalvm
- Install maven (https://maven.apache.org/install.html)

**Configure `pom.xml`**

In the `properties` section of the `pom.xml` of this project, you can adapt the setting
```<graalvm.version>20.3.0</graalvm.version>``` to the version installed on your system. Other changes should not be
necessary.

**Build Native Image**

- Make sure that maven is executed with the graalvm JDK
- In the command line, navigate to the root directory of the project
- Run ```mvn package -Pnative```
- if build was successful, the new native image is located in the ```target``` subfolder of the project
