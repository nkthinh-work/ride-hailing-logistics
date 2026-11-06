package com.rhl.payment.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ReconciliationFlaggedPayload(
        UUID recordId,
        UUID paymentId,
        String provider,
        String mismatchType,
        String localStatus,
        String providerStatus,
        BigDecimal localAmount,
        BigDecimal providerAmount,
        String details,
        Instant timestamp
) {}
