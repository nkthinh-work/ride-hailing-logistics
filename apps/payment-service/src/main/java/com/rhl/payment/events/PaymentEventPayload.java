package com.rhl.payment.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentEventPayload(
        UUID paymentId,
        UUID tripId,
        UUID customerId,
        BigDecimal amount,
        String currency,
        String paymentMethod,
        String status,
        String provider,
        String providerTransactionId,
        Instant timestamp
) {}
