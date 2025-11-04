package com.teamscale.buildbreaker.evaluation;

/** The categories in which problems are divided. */
public enum ProblemCategory {
    WARNING, ERROR, NO_PROBLEM;

    public static ProblemCategory fromRatingString(String rating) {
        switch (rating) {
            case "YELLOW":
                return WARNING;
            case "RED":
                return ERROR;
            default:
                return NO_PROBLEM;
        }
    }
}
