package com.teamscale.buildbreaker.commandline;

import picocli.CommandLine.Option;

class ThresholdEvalOptions {
    @Option(names = {"-t", "--evaluate-thresholds"}, required = true,
            description = "If this option is set, metrics from a given threshold profile will be evaluated.")
    public boolean evaluateThresholds;

    @Option(names = {"-o", "--threshold-config"}, required = true,
            description = "The name of the threshold config that should be used. Needs to be set if --evaluate-thresholds is active.")
    public String thresholdConfig;

    @Option(names = {"--fail-on-yellow-metrics"},
            description = "Whether to fail on yellow metrics (with exit code 2). Can only be used if --evaluate-thresholds is active.")
    public boolean failOnYellowMetrics;
}
