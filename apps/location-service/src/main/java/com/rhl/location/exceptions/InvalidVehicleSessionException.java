package com.rhl.location.exceptions;

public class InvalidVehicleSessionException extends RuntimeException {

    private final String errorCode;

    public InvalidVehicleSessionException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public InvalidVehicleSessionException(String message) {
        this(message, "INVALID_VEHICLE_SESSION");
    }

    public String getErrorCode() {
        return errorCode;
    }
}
