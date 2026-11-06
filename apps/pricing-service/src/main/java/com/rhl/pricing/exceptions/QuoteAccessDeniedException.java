package com.rhl.pricing.exceptions;

public class QuoteAccessDeniedException extends RuntimeException {
    public QuoteAccessDeniedException(String message) {
        super(message);
    }
}
