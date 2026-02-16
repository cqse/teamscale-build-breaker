package com.teamscale.buildbreaker.evaluation;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static com.teamscale.buildbreaker.evaluation.ProblemCategory.ERROR;
import static com.teamscale.buildbreaker.evaluation.ProblemCategory.NO_PROBLEM;
import static com.teamscale.buildbreaker.evaluation.ProblemCategory.WARNING;
import static org.assertj.core.api.Assertions.assertThat;

class MetricsEvaluatorTest {

    private final MetricsEvaluator evaluator = new MetricsEvaluator();

    @Test
    void emptyViolationListProducesNoViolations() {
        EvaluationResult result = evaluator.evaluate(Collections.emptyList(), false);
        assertThat(result.toStatusCode()).isEqualTo(0);
    }

    @Test
    void errorViolationIsAlwaysReported() {
        MetricViolation error = createViolation("Coverage", ERROR);
        EvaluationResult result = evaluator.evaluate(List.of(error), false);
        assertThat(result.toStatusCode()).isEqualTo(1);
    }

    @Test
    void warningViolationIsSkippedWhenFailOnYellowIsFalse() {
        MetricViolation warning = createViolation("Coverage", WARNING);
        EvaluationResult result = evaluator.evaluate(List.of(warning), false);
        assertThat(result.toStatusCode()).isEqualTo(0);
    }

    @Test
    void warningViolationIsReportedWhenFailOnYellowIsTrue() {
        MetricViolation warning = createViolation("Coverage", WARNING);
        EvaluationResult result = evaluator.evaluate(List.of(warning), true);
        assertThat(result.toStatusCode()).isEqualTo(2);
    }

    @Test
    void noProblemViolationDoesNotAffectStatusCode() {
        MetricViolation noProblem = createViolation("Coverage", NO_PROBLEM);
        EvaluationResult result = evaluator.evaluate(List.of(noProblem), false);
        assertThat(result.toStatusCode()).isEqualTo(0);
    }

    @Test
    void errorAndWarningMixWithFailOnYellowFalse() {
        MetricViolation error = createViolation("Complexity", ERROR);
        MetricViolation warning = createViolation("Coverage", WARNING);
        EvaluationResult result = evaluator.evaluate(List.of(error, warning), false);
        assertThat(result.toStatusCode()).isEqualTo(1);
        assertThat(result.toString()).contains("Complexity");
        assertThat(result.toString()).doesNotContain("Coverage");
    }

    @Test
    void errorAndWarningMixWithFailOnYellowTrue() {
        MetricViolation error = createViolation("Complexity", ERROR);
        MetricViolation warning = createViolation("Coverage", WARNING);
        EvaluationResult result = evaluator.evaluate(List.of(error, warning), true);
        assertThat(result.toStatusCode()).isEqualTo(1);
        assertThat(result.toString()).contains("Complexity");
        assertThat(result.toString()).contains("Coverage");
    }

    @Test
    void multipleWarningsWithFailOnYellowTrue() {
        MetricViolation warning1 = createViolation("Coverage", WARNING);
        MetricViolation warning2 = createViolation("Complexity", WARNING);
        EvaluationResult result = evaluator.evaluate(List.of(warning1, warning2), true);
        assertThat(result.toStatusCode()).isEqualTo(2);
        assertThat(result.toString()).contains("Coverage");
        assertThat(result.toString()).contains("Complexity");
    }

    private static MetricViolation createViolation(String displayName, ProblemCategory rating) {
        return new MetricViolation(displayName, "50", "80", "52", rating);
    }
}
