package com.teamscale.buildbreaker.evaluation;

public enum ProblemCategory {
    INFO, WARNING, ERROR, CRITICAL;

    public static ProblemCategory fromRatingString(String rating) {
        switch (rating) {
            case "YELLOW":
                return WARNING;
            case "RED":
                return ERROR;
        }
        throw new IllegalArgumentException("Unexpected rating string " + rating);
    }
}
