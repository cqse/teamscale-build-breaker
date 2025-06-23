package com.teamscale.buildbreaker.teamscale.client.exceptions;

public class HttpStatusCodeException extends Exception {
    private final int statusCode;
    private final String responseBody;

    public HttpStatusCodeException(int statusCode, String responseBody) {
        super("Encountered HTTP status code " + statusCode);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
