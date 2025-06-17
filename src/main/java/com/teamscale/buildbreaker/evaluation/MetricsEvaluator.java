package com.teamscale.buildbreaker.evaluation;

import java.util.List;

public class MetricsEvaluator {

    public EvaluationResult evaluate(List<MetricViolation> metricViolations, boolean failOnYellow) {
        EvaluationResult evaluationResult = new EvaluationResult();
        for (MetricViolation metricViolation : metricViolations) {
            if (metricViolation.rating == ProblemCategory.WARNING && !failOnYellow) {
                continue;
            }
            evaluationResult.addViolation(metricViolation.rating, metricViolation.toString());
        }
        return evaluationResult;
    }
}
