package com.teamscale.buildbreaker.evaluation;

import org.conqat.lib.commons.collections.SetMap;

import static com.teamscale.buildbreaker.evaluation.ProblemCategory.ERROR;
import static com.teamscale.buildbreaker.evaluation.ProblemCategory.WARNING;

public class EvaluationResult {

    private final SetMap<ProblemCategory, String> problemsByCategory = new SetMap<>();

    public void addViolation(ProblemCategory problemCategory, String violationMessage) {
        problemsByCategory.add(problemCategory, violationMessage);
    }

    @Override
    public String toString() {
        if (!hasWarnings() && !hasErrors()) {
            return "No violations detected";
        }
        StringBuilder sb = new StringBuilder();
        if (hasWarnings()) {
            sb.append("*WARNINGS*\n");
            for (String warning : problemsByCategory.getCollection(WARNING)) {
                sb.append(warning);
                sb.append("\n");
            }
        }
        if (hasErrors()) {
            if (hasWarnings()) {
                sb.append("\n");
            }
            sb.append("*ERRORS*\n");
            for (String s : problemsByCategory.getCollection(ERROR)) {
                sb.append(s);
                sb.append("\n");
            }
        }
        return sb.toString().trim();
    }

    public void addAll(EvaluationResult other) {
        problemsByCategory.addAll(other.problemsByCategory);
    }

    private boolean hasWarnings() {
        return problemsByCategory.containsCollection(WARNING);
    }

    private boolean hasErrors() {
        return problemsByCategory.containsCollection(ERROR);
    }

    public int toStatusCode() {
        if (hasErrors()) {
            return 1;
        }
        if (hasWarnings()) {
            return 2;
        }
        return 0;
    }
}