package com.rhl.dispatch.exceptions;

public class DriverOfferConflictException extends RuntimeException {

    private final String errorCode;

    public DriverOfferConflictException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
