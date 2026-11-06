package com.rhl.payment.exceptions;

import java.math.BigDecimal;

public class OverRefundException extends RuntimeException {

    private final BigDecimal requestedAmount;
    private final BigDecimal remainingAmount;

    public OverRefundException(BigDecimal requestedAmount, BigDecimal remainingAmount) {
        super("Refund amount " + requestedAmount + " exceeds remaining refundable amount " + remainingAmount);
        this.requestedAmount = requestedAmount;
        this.remainingAmount = remainingAmount;
    }

    public BigDecimal getRequestedAmount() {
        return requestedAmount;
    }

    public BigDecimal getRemainingAmount() {
        return remainingAmount;
    }
}
