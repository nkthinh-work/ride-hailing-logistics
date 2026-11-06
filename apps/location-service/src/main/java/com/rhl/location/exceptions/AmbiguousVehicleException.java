package com.rhl.location.exceptions;

public class AmbiguousVehicleException extends RuntimeException {

    private final String errorCode;

    public AmbiguousVehicleException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public AmbiguousVehicleException(String message) {
        this(message, "AMBIGUOUS_ACTIVE_VEHICLES");
    }

    public String getErrorCode() {
        return errorCode;
    }
}
