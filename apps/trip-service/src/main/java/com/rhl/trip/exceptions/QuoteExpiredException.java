package com.rhl.trip.exceptions;

import java.util.UUID;

public class QuoteExpiredException extends RuntimeException {

    public QuoteExpiredException(UUID quoteId) {
        super(String.format("Fare quote %s has expired", quoteId));
    }

    public QuoteExpiredException(String message) {
        super(message);
    }
}
