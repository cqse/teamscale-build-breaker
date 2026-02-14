package com.teamscale.buildbreaker.evaluation;

import org.conqat.lib.commons.collections.Pair;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static com.teamscale.buildbreaker.evaluation.ProblemCategory.ERROR;
import static com.teamscale.buildbreaker.evaluation.ProblemCategory.NO_PROBLEM;
import static com.teamscale.buildbreaker.evaluation.ProblemCategory.WARNING;
import static org.assertj.core.api.Assertions.assertThat;

class FindingsEvaluatorTest {

	private final FindingsEvaluator evaluator = new FindingsEvaluator();

	@Test
	void emptyFindingsProduceNoViolations() {
		EvaluationResult result = evaluator.evaluate(
				Pair.createPair(Collections.emptyList(), Collections.emptyList()), false, false);
		assertThat(result.toStatusCode()).isEqualTo(0);
	}

	@Test
	void errorFindingIsAlwaysReported() {
		Finding error = createFinding("err-1", ERROR);
		EvaluationResult result = evaluator.evaluate(
				Pair.createPair(List.of(error), Collections.emptyList()), false, false);
		assertThat(result.toStatusCode()).isEqualTo(1);
		assertThat(result.toString()).contains("err-1");
	}

	@Test
	void warningFindingIsSkippedWhenFailOnYellowIsFalse() {
		Finding warning = createFinding("warn-1", WARNING);
		EvaluationResult result = evaluator.evaluate(
				Pair.createPair(List.of(warning), Collections.emptyList()), false, false);
		assertThat(result.toStatusCode()).isEqualTo(0);
	}

	@Test
	void warningFindingIsReportedWhenFailOnYellowIsTrue() {
		Finding warning = createFinding("warn-1", WARNING);
		EvaluationResult result = evaluator.evaluate(
				Pair.createPair(List.of(warning), Collections.emptyList()), true, false);
		assertThat(result.toStatusCode()).isEqualTo(2);
		assertThat(result.toString()).contains("warn-1");
	}

	@Test
	void noProblemFindingDoesNotAffectStatusCode() {
		Finding noProblem = createFinding("np-1", NO_PROBLEM);
		EvaluationResult result = evaluator.evaluate(
				Pair.createPair(List.of(noProblem), Collections.emptyList()), true, false);
		assertThat(result.toStatusCode()).isEqualTo(0);
	}

	@Test
	void changedCodeFindingsExcludedWhenIncludeChangedCodeIsFalse() {
		Finding changedCodeError = createFinding("changed-err", ERROR);
		EvaluationResult result = evaluator.evaluate(
				Pair.createPair(Collections.emptyList(), List.of(changedCodeError)), false, false);
		assertThat(result.toStatusCode()).isEqualTo(0);
	}

	@Test
	void changedCodeFindingsIncludedWhenIncludeChangedCodeIsTrue() {
		Finding changedCodeError = createFinding("changed-err", ERROR);
		EvaluationResult result = evaluator.evaluate(
				Pair.createPair(Collections.emptyList(), List.of(changedCodeError)), false, true);
		assertThat(result.toStatusCode()).isEqualTo(1);
		assertThat(result.toString()).contains("changed-err");
	}

	@Test
	void newAndChangedCodeFindingsCombinedWhenIncludeChangedCodeIsTrue() {
		Finding newCodeError = createFinding("new-err", ERROR);
		Finding changedCodeWarning = createFinding("changed-warn", WARNING);
		EvaluationResult result = evaluator.evaluate(
				Pair.createPair(List.of(newCodeError), List.of(changedCodeWarning)), true, true);
		assertThat(result.toStatusCode()).isEqualTo(1);
		assertThat(result.toString()).contains("new-err");
		assertThat(result.toString()).contains("changed-warn");
	}

	@Test
	void onlyNewCodeFindingsEvaluatedWhenIncludeChangedCodeIsFalse() {
		Finding newCodeError = createFinding("new-err", ERROR);
		Finding changedCodeError = createFinding("changed-err", ERROR);
		EvaluationResult result = evaluator.evaluate(
				Pair.createPair(List.of(newCodeError), List.of(changedCodeError)), false, false);
		assertThat(result.toString()).contains("new-err");
		assertThat(result.toString()).doesNotContain("changed-err");
	}

	@Test
	void errorAndWarningMixWithFailOnYellowFalse() {
		Finding error = createFinding("err-1", ERROR);
		Finding warning = createFinding("warn-1", WARNING);
		EvaluationResult result = evaluator.evaluate(
				Pair.createPair(List.of(error, warning), Collections.emptyList()), false, false);
		assertThat(result.toStatusCode()).isEqualTo(1);
		assertThat(result.toString()).contains("err-1");
		assertThat(result.toString()).doesNotContain("warn-1");
	}

	@Test
	void errorAndWarningMixWithFailOnYellowTrue() {
		Finding error = createFinding("err-1", ERROR);
		Finding warning = createFinding("warn-1", WARNING);
		EvaluationResult result = evaluator.evaluate(
				Pair.createPair(List.of(error, warning), Collections.emptyList()), true, false);
		assertThat(result.toStatusCode()).isEqualTo(1);
		assertThat(result.toString()).contains("err-1");
		assertThat(result.toString()).contains("warn-1");
	}

	@Test
	void multipleWarningsWithFailOnYellowTrue() {
		Finding warning1 = createFinding("warn-1", WARNING);
		Finding warning2 = createFinding("warn-2", WARNING);
		EvaluationResult result = evaluator.evaluate(
				Pair.createPair(List.of(warning1, warning2), Collections.emptyList()), true, false);
		assertThat(result.toStatusCode()).isEqualTo(2);
		assertThat(result.toString()).contains("warn-1");
		assertThat(result.toString()).contains("warn-2");
	}

	private static Finding createFinding(String id, ProblemCategory assessment) {
		return new Finding(id, "TestGroup", "TestCategory", "Test message", "path/to/File.java", assessment);
	}
}
