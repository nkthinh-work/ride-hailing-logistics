package com.rhl.realtime.models;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Driver Offer payload contract for WebSocket delivery to driver's /user/queue/offers.
 * Sanitized for driver view: no internal secrets or customer PII.
 */
public record DriverOfferPayload(
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
) {
}
