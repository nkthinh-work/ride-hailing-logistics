package com.rhl.dispatch.exceptions;

public class TripValidationException extends RuntimeException {
    private final int status;
    private final String code;

    public TripValidationException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
