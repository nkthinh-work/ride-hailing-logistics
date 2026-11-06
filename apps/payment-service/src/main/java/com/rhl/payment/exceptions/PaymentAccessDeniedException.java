package com.rhl.payment.exceptions;

public class PaymentAccessDeniedException extends RuntimeException {
    public PaymentAccessDeniedException(String message) {
        super(message);
    }
}
