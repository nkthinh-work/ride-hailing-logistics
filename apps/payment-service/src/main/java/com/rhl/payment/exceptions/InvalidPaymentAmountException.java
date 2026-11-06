package com.rhl.payment.exceptions;

import java.math.BigDecimal;

public class InvalidPaymentAmountException extends RuntimeException {
    public InvalidPaymentAmountException(BigDecimal amount) {
        super("Payment amount must be strictly positive and finite. Received: " + amount);
    }

    public InvalidPaymentAmountException(String message) {
        super(message);
    }
}
