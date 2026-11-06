package com.rhl.trip.exceptions;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String idempotencyKey) {
        super(String.format("Idempotency key '%s' was already used for a different trip request payload", idempotencyKey));
    }

    public IdempotencyConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
