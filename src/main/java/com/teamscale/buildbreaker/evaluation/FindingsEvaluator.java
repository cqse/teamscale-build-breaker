package com.teamscale.buildbreaker.evaluation;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.teamscale.buildbreaker.exceptions.BuildBreakerInternalException;
import org.conqat.lib.commons.string.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Evaluates a finding JSON string (corresponds to Teamscale type FindingChurnList or ExtendedFindingDelta). */
public class FindingsEvaluator {

    public EvaluationResult evaluate(String findingsJson, boolean failOnYellowFindings, boolean includeChangedCode) {
        EvaluationResult evaluationResult = new EvaluationResult();
        if (StringUtils.isEmpty(findingsJson)) {
            return evaluationResult;
        }

        DocumentContext findings = JsonPath.parse(findingsJson);
        List<Map<String, Object>> findingsToEvaluate = new java.util.ArrayList<>();

        // Try to parse as FindingChurnList (from finding-churn/list API)
        try {
            List<Map<String, Object>> addedFindings = findings.read("$..addedFindings.*");
            findingsToEvaluate.addAll(addedFindings);

            if (includeChangedCode) {
                List<Map<String, Object>> findingsInChangedCode = findings.read("$..findingsInChangedCode.*");
                findingsToEvaluate.addAll(findingsInChangedCode);
            }
        } catch (Exception e) {
            // If parsing as FindingChurnList fails, try parsing as ExtendedFindingDelta (from findings/delta API)
            try {
                List<Map<String, Object>> addedFindings = findings.read("$.addedFindings.*");
                findingsToEvaluate.addAll(addedFindings);

                if (includeChangedCode) {
                    List<Map<String, Object>> findingsInChangedCode = findings.read("$.findingsInChangedCode.*");
                    findingsToEvaluate.addAll(findingsInChangedCode);
                }
            } catch (Exception ex) {
                throw new BuildBreakerInternalException(
                        "Could not parse JSON response as either FindingChurnList or ExtendedFindingDelta:\n" + 
                        findingsJson + "\n\nPlease contact CQSE with an error report.",
                        ex);
            }
        }

        try {
            for (Map<String, Object> finding : findingsToEvaluate) {
                Map<String, String> location = (Map<String, String>) finding.getOrDefault("location", new HashMap<>());
                String message =
                        String.format("Finding %s:\n\tGroup: %s: \n\tCategory: %s\n\tMessage: %s\n\tLocation: %s",
                                finding.get("id"), finding.get("groupName"), finding.get("categoryName"),
                                finding.get("message"), location.getOrDefault("uniformPath", "<undefined>"));
                ProblemCategory assessment = ProblemCategory.fromRatingString((String) finding.get("assessment"));
                if (assessment == ProblemCategory.WARNING && !failOnYellowFindings) {
                    continue;
                }
                evaluationResult.addViolation(assessment, message);
            }
        } catch (ClassCastException e) {
            throw new BuildBreakerInternalException(
                    "Could not parse JSON response:\n" + findingsJson + "\n\nPlease contact CQSE with an error report.",
                    e);
        }
        return evaluationResult;
    }

}
