package com.teamscale.buildbreaker.client.exceptions;

import okhttp3.Response;

public class HttpStatusCodeException extends Exception {
    private final int statusCode;
    private final Response response;

    public HttpStatusCodeException(int statusCode, Response response) {
        super("Encountered HTTP status code " + statusCode);
        this.statusCode = statusCode;
        this.response = response;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public Response getResponse() {
        return response;
    }
}
