package com.fredvested.web.email;

/**
 * A signup's projection failed validation. The message is a short reason code
 * (ProjectionValidator), never a submitted value, so it is safe to log.
 */
public class InvalidProjectionException extends RuntimeException {

    public InvalidProjectionException(String reason) {
        super(reason);
    }

    public String reason() {
        return getMessage();
    }
}
