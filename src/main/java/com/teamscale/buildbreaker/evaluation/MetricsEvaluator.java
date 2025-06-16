package com.teamscale.buildbreaker.evaluation;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.teamscale.buildbreaker.exceptions.BuildBreakerInternalException;

import java.util.List;
import java.util.Locale;
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
				Map<String, String> schemaEntry = (Map<String, String>) metricViolation.get("schemaEntry");
				String thresholdYellow = String.valueOf(metricThresholds.get("thresholdYellow"));
				String value = printValue(metricViolation.get("value"), ValueType.valueOf(schemaEntry.get("valueType")));
                String message = String.format("%s: \n\tThresholds (yellow/red): %s/%s\n\tActual value: %s",
                        metricViolation.get("displayName"), (thresholdYellow != null) ? "-" : thresholdYellow,
                        metricThresholds.get("thresholdRed"), value);
                ProblemCategory rating = ProblemCategory.fromRatingString((String) metricViolation.get("rating"));
                if (rating == ProblemCategory.ERROR ||
					rating == ProblemCategory.WARNING && failOnYellow) {
					evaluationResult.addViolation(rating, message);
                }
            }
        } catch (ClassCastException e) {
            throw new BuildBreakerInternalException("Could not parse JSON response:\n" + metricAssessmentsJson +
                    "\n\nPlease contact CQSE with an error report.", e);
        }
        return evaluationResult;
    }

	/**
	 * Use Locale.ENGLISH to always print a decimal point
	 */
	private String printValue(Object value, ValueType type) {
		switch (type) {
			case STRING:
			case COUNTER_SET:
			case TIMESTAMP:
				return String.valueOf(value);
			case NUMERIC:
				return String.format(Locale.ENGLISH, "%.4f", value);
			case ASSESSMENT:
				Map<String, List<Integer>> valueMap = (Map<String, List<Integer>>) value;
				List<Integer> assessmentInts = valueMap.get("mapping");
				return String.format(Locale.ENGLISH, "%.4f (Red: %s, Green: %s) ",
					assessmentInts.get(0).floatValue() / assessmentInts.get(3),
					assessmentInts.get(0),
					assessmentInts.get(3));
		}
		return String.valueOf(value);
	}
}
