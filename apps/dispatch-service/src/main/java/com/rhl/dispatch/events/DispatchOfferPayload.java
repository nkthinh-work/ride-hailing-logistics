package com.rhl.dispatch.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DispatchOfferPayload(
        UUID offerId,
        UUID tripId,
        UUID driverId,
        String serviceType,
        double pickupLatitude,
        double pickupLongitude,
        String pickupAddress,
        String dropoffAddress,
        Double estimatedDistanceKm,
        BigDecimal estimatedFare,
        Instant offeredAt,
        Instant expiresAt,
        String status
) {}
