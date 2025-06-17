package com.teamscale.buildbreaker.evaluation;

/**
 * TODO
 */
public class Finding {
    public final String id;
    public final String group;
    public final String category;
    public final String message;
    public final String uniformPath;
    public final ProblemCategory assessment;

    /**
     * TODO
     *
     * @param id
     * @param group
     * @param category
     * @param message
     * @param uniformPath
     * @param assessment
     */
    public Finding(String id, String group, String category, String message, String uniformPath, ProblemCategory assessment) {
        this.id = id;
        this.group = group;
        this.category = category;
        this.message = message;
        this.uniformPath = uniformPath;
        this.assessment = assessment;
    }

    @Override
    public String toString() {
        return String.format("Finding %s:\n\tGroup: %s: \n\tCategory: %s\n\tMessage: %s\n\tLocation: %s", id, group, category, message, uniformPath);
    }
}
