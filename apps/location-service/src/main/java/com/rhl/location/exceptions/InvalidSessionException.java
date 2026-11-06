package com.rhl.location.exceptions;

public class InvalidSessionException extends RuntimeException {

    private final String errorCode;

    public InvalidSessionException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public InvalidSessionException(String message) {
        this(message, "STALE_SESSION_PACKET");
    }

    public String getErrorCode() {
        return errorCode;
    }
}
