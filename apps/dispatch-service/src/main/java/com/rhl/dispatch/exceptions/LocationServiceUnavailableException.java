package com.rhl.dispatch.exceptions;

public class LocationServiceUnavailableException extends RuntimeException {

    public LocationServiceUnavailableException(String message) {
        super(message);
    }

    public LocationServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
