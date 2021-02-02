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

*TODO* Imho we should generate this part with pico cli. GitHub neatly formats *.adoc/ad/asciidoc files :) https://github.com/remkop/picocli/tree/master/picocli-codegen#generate-documentation

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
00 UTC Thursday, 1 January 1970 or the string
*HEAD* to evaluate thresholds on the latest revision on that branch.

Format: BRANCH:TIMESTAMP

Examples: master:1597845930000 or develop:HEAD

**-c**, **--commit**=*&lt;commit-revision&gt;*  
The version control commit revision for which analysis results should be obtained. This is typically the commit that the
current CI pipeline is building. Can be either a Git SHA1, a SVN revision number or a Team Foundation changeset ID.

*TODO* In the ts-upload we named this `--insecure` which aligns with curl

**--disable-ssl-validation**  
By default, SSL certificates are validated against the configured KeyStore. This flag disables validation which makes
using this tool with self-signed certificates easier.

**-f**, **--evaluate-findings**  
If this option is set, findings introduced with the given commit will be evaluated.

**--fail-on-modified-code-findings**  
Fail on findings in modified code (not just findings in new code).

**--fail-on-yellow-findings**  
Whether to fail on yellow findings.

**--fail-on-yellow-metrics**  
Whether to fail on yellow metrics.

**-h**, **--help**  
Show this help message and exit.

**-o**, **--threshold-config**=*&lt;thresholdConfig&gt;*  
The name of the threshold config that should be used.

**-t**, **--evaluate-thresholds**  
If this option is set, metrics from a given threshold profile will be evaluated.

**--trusted-keystore**=*&lt;keystore-path;password&gt;*  
A Java KeyStore file and its corresponding password. The KeyStore contains additional certificates that should be
trusted when performing SSL requests. Separate the path from the password with a semicolon, e.g:

/path/to/keystore.jks;PASSWORD

The path to the KeyStore must not contain a semicolon. Cannot be used in conjunction with --disable-ssl-validation.

**-V**, **--version**  
Print version information and exit.

**Exit codes**

- 0: successful evaluation, no violations detected
- 1: errors detected
- 2: warnings detected (when evaluation of warnings is enabled)
- -1, or other negative number: an internal error occurred, please contact the developers

Running the native image only with --help/--version returns the help message/version of the native image.

## Building the Native Image

**Prerequisites**

- Install graalvm (https://www.graalvm.org/getting-started/)
- Install native-image extension of graalvm
- Install maven (https://maven.apache.org/install.html)

**Configure `pom.xml`**

In the `properties` section of the `pom.xml` of this project, you can adapt the setting
```<graalvm.version>20.3.0</graalvm.version>``` to the version installed on your system. Other changes should not be
necessary.

**Build Native Image**

- Make sure that maven is executed with the graalvm JDK
- In the command line, navigate to the root directory of the project
- Run ```mvn package```
- if build was successful, the new native image is located in the ```target``` subfolder of the project
