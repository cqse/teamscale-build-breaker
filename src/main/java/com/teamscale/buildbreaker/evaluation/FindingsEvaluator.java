package com.teamscale.buildbreaker.evaluation;

import com.teamscale.buildbreaker.exceptions.BuildBreakerInternalException;
import org.conqat.lib.commons.collections.Pair;

import java.util.ArrayList;
import java.util.List;

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

    public EvaluationResult evaluate(Pair<List<Finding>, List<Finding>> findings, boolean failOnYellowFindings, boolean includeChangedCode) {
        EvaluationResult evaluationResult = new EvaluationResult();

        List<Finding> findingsToEvaluate = new ArrayList<>(findings.getFirst());
        if (includeChangedCode) {
            findingsToEvaluate.addAll(findings.getSecond());
        }

        try {
            for (Finding finding : findingsToEvaluate) {
                if (finding.assessment == ProblemCategory.WARNING && !failOnYellowFindings) {
                    continue;
                }
                evaluationResult.addViolation(finding.assessment, finding.toString());
            }
        } catch (ClassCastException e) {
            throw new BuildBreakerInternalException(
                    "Could not parse JSON response:\n" + findings + "\n\nPlease contact CQSE with an error report.",
                    e);
        }
        return evaluationResult;
    }
}
