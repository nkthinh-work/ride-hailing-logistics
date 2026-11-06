package com.rhl.payment.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when an untrusted or tampered webhook signature / credentials mismatch occurs.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class PaymentSecurityException extends RuntimeException {
    public PaymentSecurityException(String message) {
        super(message);
    }
}
