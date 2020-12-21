package com.teamscale.buildbreaker;

import org.conqat.lib.commons.collections.SetMap;

import static com.teamscale.buildbreaker.ProblemCategory.ERROR;
import static com.teamscale.buildbreaker.ProblemCategory.WARNING;

public class EvaluationResult {

    private final SetMap<ProblemCategory, String> problemsByCategory = new SetMap<>();

    public void addWarning(String warningMessage) {
        problemsByCategory.add(WARNING, warningMessage);
    }

    public void addError(String errorMessage) {
        problemsByCategory.add(ERROR, errorMessage);
    }

    public void addViolation(ProblemCategory problemCategory, String violationMessage) {
        problemsByCategory.add(problemCategory, violationMessage);
    }

    @Override
    public String toString() {
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

    private boolean hasWarnings() {
        return problemsByCategory.containsCollection(WARNING);
    }

    private boolean hasErrors() {
        return problemsByCategory.containsCollection(ERROR);
    }
}
