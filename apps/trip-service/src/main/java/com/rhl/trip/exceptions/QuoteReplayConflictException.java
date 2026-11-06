package com.rhl.trip.exceptions;

import java.util.UUID;

public class QuoteReplayConflictException extends RuntimeException {

    public QuoteReplayConflictException(UUID quoteId) {
        super(String.format("Fare quote %s has already been used to create a trip (quote replay blocked)", quoteId));
    }

    public QuoteReplayConflictException(String message) {
        super(message);
    }
}
