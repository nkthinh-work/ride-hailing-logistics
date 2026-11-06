package com.rhl.trip.exceptions;

public class TripAccessDeniedException extends RuntimeException {

    public TripAccessDeniedException(String message) {
        super(message);
    }

    public TripAccessDeniedException(java.util.UUID tripId, java.util.UUID customerId) {
        super(String.format("Trip %s does not belong to customer %s", tripId, customerId));
    }
}
