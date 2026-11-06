package com.rhl.payment.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RefundEventPayload(
        UUID refundId,
        UUID paymentId,
        UUID tripId,
        UUID customerId,
        BigDecimal amount,
        String currency,
        String status,
        String reason,
        String provider,
        String providerRefundTransactionId,
        Instant timestamp
) {}
