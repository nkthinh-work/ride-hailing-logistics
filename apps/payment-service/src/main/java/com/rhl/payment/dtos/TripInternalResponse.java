package com.rhl.payment.dtos;

import java.math.BigDecimal;
import java.util.UUID;

public record TripInternalResponse(
        UUID tripId,
        UUID customerId,
        UUID driverId,
        String status,
        BigDecimal estimatedFare,
        String currency,
        BigDecimal cancellationFee
) {}
