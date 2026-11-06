package com.rhl.payment.dtos;

import java.math.BigDecimal;
import java.util.UUID;

public record RefundEligibilityResponse(
        UUID paymentId,
        boolean eligible,
        BigDecimal originalPaidAmount,
        BigDecimal totalRefundedAmount,
        BigDecimal inFlightRefundAmount,
        BigDecimal remainingRefundableAmount,
        String currency,
        String reason,
        String policyMode
) {}
