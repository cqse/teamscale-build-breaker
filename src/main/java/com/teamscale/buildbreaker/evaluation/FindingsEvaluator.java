package com.teamscale.buildbreaker.evaluation;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import org.conqat.lib.commons.string.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FindingsEvaluator {

    public EvaluationResult evaluate(String findingsJson, boolean failOnYellowFindings, boolean includeChangedCode) {
        EvaluationResult evaluationResult = new EvaluationResult();
        if (StringUtils.isEmpty(findingsJson)) {
            return evaluationResult;
        }
        DocumentContext findings = JsonPath.parse(findingsJson);
        List<Map<String, Object>> findingsToEvaluate = findings.read("$..addedFindings.*");
        if (includeChangedCode) {
            findingsToEvaluate.addAll(findings.read("$..findingsInChangedCode"));
        }
        for (Map<String, Object> finding : findingsToEvaluate) {
            Map<String, String> location = (Map<String, String>) finding.getOrDefault("location", new HashMap<>());
            String message = String.format("Finding %s:\n\tGroup: %s: \n\tCategory: %s\n\tMessage: %s\n\tLocation: %s", finding.get("id"),
                    finding.get("groupName"),
                    finding.get("categoryName"),
                    finding.get("message"),
                    location.getOrDefault("uniformPath", "<undefined>"));
            ProblemCategory assessment = ProblemCategory.fromRatingString((String) finding.get("assessment"));
            if (assessment == ProblemCategory.WARNING && !failOnYellowFindings) {
                continue;
            }
            evaluationResult.addViolation(assessment, message);
        }
        return evaluationResult;
    }

}
