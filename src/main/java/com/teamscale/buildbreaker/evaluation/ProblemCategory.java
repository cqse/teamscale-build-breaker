package com.teamscale.buildbreaker.evaluation;

/** The categories in which problems are divided. */
public enum ProblemCategory {
    NONE, WARNING, ERROR;

    public static ProblemCategory fromRatingString(String rating) {
        switch (rating) {
			case "GREEN":
			case "UNKNOWN":
				return NONE;
            case "YELLOW":
                return WARNING;
            case "RED":
                return ERROR;
        }
        throw new IllegalArgumentException("Unexpected rating string " + rating);
    }
}
