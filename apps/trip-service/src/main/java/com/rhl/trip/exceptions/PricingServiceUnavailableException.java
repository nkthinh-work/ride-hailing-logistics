package com.rhl.trip.exceptions;

public class PricingServiceUnavailableException extends RuntimeException {

    public PricingServiceUnavailableException(String message) {
        super(message);
    }

    public PricingServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
