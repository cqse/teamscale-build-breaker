package com.teamscale.buildbreaker.teamscale_client.exceptions;

public class HttpRedirectException extends Exception {
    private final String redirectLocation;

    public HttpRedirectException(String redirectLocation) {
        super("Encountered redirect to " + redirectLocation);
        this.redirectLocation = redirectLocation;
    }

    public String getRedirectLocation() {
        return redirectLocation;
    }
}
