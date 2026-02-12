package com.teamscale.buildbreaker.teamscale_client;

/**
 * Mirrors the response of the {@code /api/projects/{project}/branch-analysis-state/{branch}} endpoint.
 */
public class AnalysisState {
    public final long timestamp;
    public final String state;
    public final String rollbackId;

    public AnalysisState(long timestamp, String state, String rollbackId) {
        this.timestamp = timestamp;
        this.state = state;
        this.rollbackId = rollbackId;
    }
}
