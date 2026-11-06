package com.rhl.payment.exceptions;

import java.util.UUID;

public class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException(UUID paymentId) {
        super("Payment transaction not found with ID: " + paymentId);
    }

    public PaymentNotFoundException(String message) {
        super(message);
    }
}
