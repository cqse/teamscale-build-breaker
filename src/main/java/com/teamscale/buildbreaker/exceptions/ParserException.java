package com.teamscale.buildbreaker.exceptions;

import java.io.IOException;

public class ParserException extends IOException {
    public ParserException(String message, Throwable cause) {
        super(message, cause);
    }

    public ParserException(Throwable cause) {
        super(cause);
    }
}
