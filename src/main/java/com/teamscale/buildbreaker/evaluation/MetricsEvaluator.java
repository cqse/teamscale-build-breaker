package com.teamscale.buildbreaker.evaluation;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.teamscale.buildbreaker.exceptions.BuildBreakerInternalException;

import java.util.List;
import java.util.Map;

/** Evaluates a metric assessment JSON string (corresponds to Teamscale type List<GroupAssessment>). */
public class MetricsEvaluator {

    public EvaluationResult evaluate(String metricAssessmentsJson, boolean failOnYellow) {
        EvaluationResult evaluationResult = new EvaluationResult();
        try {
            DocumentContext metricAssessments = JsonPath.parse(metricAssessmentsJson);
            List<Map<String, Object>> metricViolations = metricAssessments.read("$..metrics.*");
            for (Map<String, Object> metricViolation : metricViolations) {
                Map<String, String> metricThresholds = (Map<String, String>) metricViolation.get("metricThresholds");
                String message = String.format("%s: \n\tThresholds (yellow/red): %s/%s\n\tActual value: %s",
                        metricViolation.get("displayName"), metricThresholds.get("thresholdYellow"),
                        metricThresholds.get("thresholdRed"), metricViolation.get("formattedTextValue"));
                ProblemCategory rating = ProblemCategory.fromRatingString((String) metricViolation.get("rating"));
                if (rating == ProblemCategory.WARNING && !failOnYellow) {
                    continue;
                }
                evaluationResult.addViolation(rating, message);
            }
        } catch (ClassCastException e) {
            throw new BuildBreakerInternalException("Could not parse JSON response:\n" + metricAssessmentsJson +
                    "\n\nPlease contact CQSE with an error report.", e);
        }
        return evaluationResult;
    }
}
