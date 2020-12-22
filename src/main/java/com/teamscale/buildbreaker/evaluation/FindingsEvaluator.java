package com.teamscale.buildbreaker.evaluation;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import java.util.List;
import java.util.Map;

public class FindingsEvaluator implements Evaluator {

    @Override
    public EvaluationResult evaluate(String findingsJson) {
        EvaluationResult evaluationResult = new EvaluationResult();
        DocumentContext findings = JsonPath.parse(findingsJson);
        List<Map<String, Object>> metricViolations = findings.read("$..findings[?(@.rating =~ /(YELLOW|RED)/)]");
        for (Map<String, Object> metricViolation : metricViolations) {
            Map<String, String> metricThresholds = (Map<String, String>) metricViolation.get("metricThresholds");
            String message = String.format("%s: \n\tThresholds (yellow/red): %s/%s\n\tActual value: %s", metricViolation.get("displayName"),
                    metricThresholds.get("thresholdYellow"),
                    metricThresholds.get("thresholdRed"),
                    metricViolation.get("formattedTextValue"));
            evaluationResult.addViolation(ProblemCategory.fromRatingString((String) metricViolation.get("rating")), message);
        }
        return evaluationResult;
    }
}
