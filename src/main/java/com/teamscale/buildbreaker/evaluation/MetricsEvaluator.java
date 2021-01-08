package com.teamscale.buildbreaker.evaluation;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import java.util.List;
import java.util.Map;

public class MetricsEvaluator {

    public EvaluationResult evaluate(String metricAssessmentsJson, boolean failOnYellow) {
        EvaluationResult evaluationResult = new EvaluationResult();
        DocumentContext metricAssessments = JsonPath.parse(metricAssessmentsJson);
        List<Map<String, Object>> metricViolations = metricAssessments.read("$..metrics.*");
        for (Map<String, Object> metricViolation : metricViolations) {
            Map<String, String> metricThresholds = (Map<String, String>) metricViolation.get("metricThresholds");
            String message = String.format("%s: \n\tThresholds (yellow/red): %s/%s\n\tActual value: %s", metricViolation.get("displayName"),
                    metricThresholds.get("thresholdYellow"),
                    metricThresholds.get("thresholdRed"),
                    metricViolation.get("formattedTextValue"));
            ProblemCategory rating = ProblemCategory.fromRatingString((String) metricViolation.get("rating"));
            if (rating == ProblemCategory.WARNING && !failOnYellow) {
                continue;
            }
            evaluationResult.addViolation(rating, message);
        }
        return evaluationResult;
    }
}
