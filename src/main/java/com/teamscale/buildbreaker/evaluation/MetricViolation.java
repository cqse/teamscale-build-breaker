package com.teamscale.buildbreaker.evaluation;

/**
 * TODO
 */
public class MetricViolation {
    public final String displayName;
    public final String yellowThreshold;
    public final String redThreshold;
    public final String formattedTextValue;
    public final ProblemCategory rating;

    /**
     * TODO
     *
     * @param displayName
     * @param yellowThreshold
     * @param redThreshold
     * @param formattedTextValue
     * @param rating
     */
    public MetricViolation(String displayName, String yellowThreshold, String redThreshold, String formattedTextValue, ProblemCategory rating) {
        this.displayName = displayName;
        this.yellowThreshold = yellowThreshold;
        this.redThreshold = redThreshold;
        this.formattedTextValue = formattedTextValue;
        this.rating = rating;
    }

    @Override
    public String toString() {
        return String.format("%s: \n\tThresholds (yellow/red): %s/%s\n\tActual value: %s",
                displayName, yellowThreshold, redThreshold, formattedTextValue);
    }
}
