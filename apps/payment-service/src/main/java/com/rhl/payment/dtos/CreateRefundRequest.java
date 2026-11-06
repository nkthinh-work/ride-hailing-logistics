package com.rhl.payment.dtos;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record CreateRefundRequest(
        @DecimalMin(value = "0.01", message = "Refund amount must be positive if provided")
        BigDecimal amount,

        @NotBlank(message = "Refund reason is required")
        String reason
) {}
