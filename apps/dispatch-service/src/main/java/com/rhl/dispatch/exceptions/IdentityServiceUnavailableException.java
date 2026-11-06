package com.rhl.dispatch.exceptions;

public class IdentityServiceUnavailableException extends RuntimeException {

    public IdentityServiceUnavailableException(String message) {
        super(message);
    }

    public IdentityServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
