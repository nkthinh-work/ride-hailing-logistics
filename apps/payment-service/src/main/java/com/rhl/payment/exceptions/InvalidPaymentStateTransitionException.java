package com.rhl.payment.exceptions;

import com.rhl.payment.enums.PaymentStatus;

public class InvalidPaymentStateTransitionException extends RuntimeException {
    public InvalidPaymentStateTransitionException(PaymentStatus currentStatus, PaymentStatus targetStatus) {
        super(String.format("Invalid payment state transition from %s to %s", currentStatus, targetStatus));
    }

    public InvalidPaymentStateTransitionException(String message) {
        super(message);
    }
}
