package com.rhl.pricing.exceptions;

public class QuoteExpiredException extends RuntimeException {
    public QuoteExpiredException(String message) {
        super(message);
    }
}
