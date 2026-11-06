package com.rhl.payment.exceptions;

public class RefundNotEligibleException extends RuntimeException {
    public RefundNotEligibleException(String message) {
        super(message);
    }
}
