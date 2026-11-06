package com.rhl.trip.dtos;

import com.rhl.trip.models.ServiceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Customer booking request payload (M05 Group 2).
 * Note: Financial figures (fare, rate, surge multiplier) are NOT accepted from client (Fail-Safe Security).
 */
public record CreateTripRequest(
        @NotNull(message = "quoteId is required")
        UUID quoteId,

        @NotBlank(message = "idempotencyKey is required")
        @Size(max = 128, message = "idempotencyKey must not exceed 128 characters")
        String idempotencyKey,

        @NotNull(message = "serviceType is required")
        ServiceType serviceType,

        @NotBlank(message = "vehicleType is required")
        String vehicleType,

        boolean customerConfirmedSurge,

        // Optional coordinates for cross-checking against quote
        BigDecimal pickupLatitude,
        BigDecimal pickupLongitude,
        BigDecimal dropoffLatitude,
        BigDecimal dropoffLongitude
) {
}
