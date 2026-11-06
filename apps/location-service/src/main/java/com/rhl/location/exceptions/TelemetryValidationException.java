package com.rhl.location.exceptions;

public class TelemetryValidationException extends RuntimeException {

    private final String errorCode;

    public TelemetryValidationException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
