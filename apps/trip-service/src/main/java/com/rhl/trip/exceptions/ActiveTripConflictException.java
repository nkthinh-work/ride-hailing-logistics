package com.rhl.trip.exceptions;

import java.util.UUID;

public class ActiveTripConflictException extends RuntimeException {

    public ActiveTripConflictException(UUID customerId) {
        super("Customer " + customerId + " already has an active conflicting trip");
    }
}
