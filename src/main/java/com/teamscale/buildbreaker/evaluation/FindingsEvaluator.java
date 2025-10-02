package com.teamscale.buildbreaker.evaluation;

import org.conqat.lib.commons.collections.Pair;

import java.util.ArrayList;
import java.util.List;

public class FindingsEvaluator {

    public EvaluationResult evaluate(Pair<List<Finding>, List<Finding>> findings, boolean failOnYellowFindings, boolean includeChangedCode) {
        EvaluationResult evaluationResult = new EvaluationResult();

        List<Finding> findingsToEvaluate = new ArrayList<>(findings.getFirst());
        if (includeChangedCode) {
            findingsToEvaluate.addAll(findings.getSecond());
        }

        for (Finding finding : findingsToEvaluate) {
            if (finding.assessment == ProblemCategory.WARNING && !failOnYellowFindings) {
                continue;
            }
            evaluationResult.addViolation(finding.assessment, finding.toString());
        }
        return evaluationResult;
    }
}
