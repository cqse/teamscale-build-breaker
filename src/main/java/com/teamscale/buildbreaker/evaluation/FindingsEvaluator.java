package com.teamscale.buildbreaker.evaluation;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.teamscale.buildbreaker.exceptions.BuildBreakerInternalException;
import org.conqat.lib.commons.string.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates a finding JSON string.
 * <p>
 * This class can handle two different response formats:
 * <ul>
 *   <li>FindingChurnList - from the finding-churn/list endpoint (for single commit evaluation)</li>
 *   <li>ExtendedFindingDelta - from the findings/delta endpoint (for branch comparison)</li>
 * </ul>
 * The class automatically detects which format is being used and processes it accordingly.
 */
public class FindingsEvaluator {

    public EvaluationResult evaluate(String findingsJson, boolean failOnYellowFindings, boolean includeChangedCode) {
        EvaluationResult evaluationResult = new EvaluationResult();
        if (StringUtils.isEmpty(findingsJson)) {
            return evaluationResult;
        }

        DocumentContext findings = JsonPath.parse(findingsJson);

        List<Map<String, Object>> findingsToEvaluate = new ArrayList<>(getAddedFindingsFromChurnList(findings));
        if (includeChangedCode) {
            findingsToEvaluate.addAll(getChangedFindingsFromChurnList(findings));
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

    /**
     * Gets added findings from the finding-churn/list response.
     */
    private List<Map<String, Object>> getAddedFindingsFromChurnList(DocumentContext findings) {
        try {
            return findings.read("$..addedFindings.*");
        } catch (PathNotFoundException e) {
            return new ArrayList<>();
        }
    }

    /**
     * Gets findings in changed code from the finding-churn/list response.
     */
    private List<Map<String, Object>> getChangedFindingsFromChurnList(DocumentContext findings) {
        try {
            return findings.read("$..findingsInChangedCode.*");
        } catch (PathNotFoundException e) {
            return new ArrayList<>();
        }
    }
}
