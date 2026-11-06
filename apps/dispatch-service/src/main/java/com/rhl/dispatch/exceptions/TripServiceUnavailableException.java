package com.rhl.dispatch.exceptions;

public class TripServiceUnavailableException extends RuntimeException {

    public TripServiceUnavailableException(String message) {
        super(message);
    }

    public TripServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
