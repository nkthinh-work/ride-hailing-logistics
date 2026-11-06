package com.rhl.trip.exceptions;

import com.rhl.trip.models.TripStatus;

public class InvalidTripStateTransitionException extends RuntimeException {

    private final TripStatus fromStatus;
    private final TripStatus toStatus;

    public InvalidTripStateTransitionException(TripStatus fromStatus, TripStatus toStatus, String message) {
        super(message);
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
    }

    public TripStatus getFromStatus() {
        return fromStatus;
    }

    public TripStatus getToStatus() {
        return toStatus;
    }
}
