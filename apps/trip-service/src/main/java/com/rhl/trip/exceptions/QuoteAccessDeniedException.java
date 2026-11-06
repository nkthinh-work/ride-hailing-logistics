package com.rhl.trip.exceptions;

import java.util.UUID;

public class QuoteAccessDeniedException extends RuntimeException {

    public QuoteAccessDeniedException(UUID quoteId, UUID customerId) {
        super(String.format("Fare quote %s does not belong to customer %s", quoteId, customerId));
    }

    public QuoteAccessDeniedException(String message) {
        super(message);
    }
}
