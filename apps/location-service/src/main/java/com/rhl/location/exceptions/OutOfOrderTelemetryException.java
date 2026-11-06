package com.rhl.location.exceptions;

public class OutOfOrderTelemetryException extends RuntimeException {

    private final String errorCode;

    public OutOfOrderTelemetryException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
