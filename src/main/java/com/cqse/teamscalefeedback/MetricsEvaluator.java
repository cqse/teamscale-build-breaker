package com.cqse.teamscalefeedback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teamscale.client.model.MetricAssessment;

import java.io.IOException;

public class MetricsEvaluator {
    public EvaluationResult evaluate(MetricAssessment[] metricAssessments) {
        return new EvaluationResult();
    }

    private int evaluateResponse(boolean failOnYellow, String unparsedResponse) throws IOException {
        int exitCode = 0;

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode response = objectMapper.readTree(unparsedResponse);

        if (response.size() == 0) {
            System.out.println("WARNING: The data is unavailable. No metrics and thresholds were evaluated.");
            exitCode = 2;
        }
        for (JsonNode group : response) {
            String groupRating = group.get("rating").asText();
            if (groupRating.equals("RED") || (failOnYellow && groupRating.equals("YELLOW"))) {
                exitCode = 3;
                System.out.println("Violation in group " + group.get("name") + ":");
                JsonNode metrics = group.get("metrics");
                for (JsonNode metric : metrics) {
                    String metricRating = metric.get("rating").asText();
                    if (metricRating.equals("RED")) {
                        System.out.println(metricRating + " " + metric.get("displayName").asText() + ": red-threshold-value "
                                + metric.get("metricThresholds").get("thresholdRed").asDouble() + ", current-value "
                                + metric.get("formattedTextValue"));
                    } else if (failOnYellow && metricRating.equals("YELLOW")) {
                        System.out.println(metricRating + " " + metric.get("displayName").asText() + ": yellow-threshold-value "
                                + metric.get("metricThresholds").get("thresholdYellow").asDouble() + ", current-value "
                                + metric.get("formattedTextValue"));
                    }
                }
            }
        }
        if (exitCode == 0) {
            System.out.println("All metrics passed the evaluation.");
        }
        return exitCode;
    }
}
