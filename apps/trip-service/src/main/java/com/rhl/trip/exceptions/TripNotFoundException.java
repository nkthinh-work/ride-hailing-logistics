package com.rhl.trip.exceptions;

import java.util.UUID;

public class TripNotFoundException extends RuntimeException {

    public TripNotFoundException(UUID tripId) {
        super("Trip not found with id: " + tripId);
    }

    public TripNotFoundException(String message) {
        super(message);
    }
}
