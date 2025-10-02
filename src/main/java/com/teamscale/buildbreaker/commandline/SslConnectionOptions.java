package com.teamscale.buildbreaker.commandline;

import org.conqat.lib.commons.string.StringUtils;
import static picocli.CommandLine.Option;
import static picocli.CommandLine.ParameterException;

class SslConnectionOptions {
    @Option(names = "--insecure",
            description = "By default, SSL certificates are validated against the configured KeyStore." +
                    " This flag disables validation which makes using this tool with self-signed certificates easier.")
    public boolean disableSslValidation;

    public String keyStorePath;

    public String keyStorePassword;

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
            throw new ParameterException(BuildBreaker.spec.commandLine(), "You must supply a valid KeyStore path.");
        }
        this.keyStorePassword = keystoreAndPasswordSplit[1];
    }

}
