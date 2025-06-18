package com.teamscale.buildbreaker.teamscale.client.exceptions;

public class HttpStatusCodeException extends Exception {
    private final int statusCode;
    private final String response;

    public HttpStatusCodeException(int statusCode, String response) {
        super("Encountered HTTP status code " + statusCode);
        this.statusCode = statusCode;
        this.response = response;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponse() {
        return response;
    }
}
