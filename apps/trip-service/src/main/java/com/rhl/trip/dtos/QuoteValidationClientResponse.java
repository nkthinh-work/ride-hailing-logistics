package com.rhl.trip.dtos;

import com.rhl.trip.models.ServiceType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record QuoteValidationClientResponse(
        UUID quoteId,
        UUID customerId,
        ServiceType serviceType,
        String vehicleType,
        String regionId,
        BigDecimal pickupLatitude,
        BigDecimal pickupLongitude,
        String pickupAddress,
        BigDecimal dropoffLatitude,
        BigDecimal dropoffLongitude,
        String dropoffAddress,
        BigDecimal estimatedDistanceKm,
        Integer estimatedDurationMinutes,
        BigDecimal estimatedFare,
        BigDecimal surgeMultiplier,
        String currency,
        String pricingRuleVersion,
        Instant expiresAt,
        boolean isSurgeApplied
) {
}
