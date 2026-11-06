package com.rhl.payment.exceptions;

import java.util.UUID;

public class TripNotPayableException extends RuntimeException {
    public TripNotPayableException(UUID tripId, String reason) {
        super(String.format("Trip %s is not eligible for payment: %s", tripId, reason));
    }

    public TripNotPayableException(String message) {
        super(message);
    }
}
