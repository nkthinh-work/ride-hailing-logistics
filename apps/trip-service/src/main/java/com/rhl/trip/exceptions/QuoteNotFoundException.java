package com.rhl.trip.exceptions;

import java.util.UUID;

public class QuoteNotFoundException extends RuntimeException {

    public QuoteNotFoundException(UUID quoteId) {
        super(String.format("Fare quote %s was not found", quoteId));
    }

    public QuoteNotFoundException(String message) {
        super(message);
    }
}
