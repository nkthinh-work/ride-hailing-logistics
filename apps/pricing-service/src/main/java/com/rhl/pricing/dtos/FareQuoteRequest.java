package com.rhl.pricing.dtos;

import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request payload for calculating and persisting an immutable Fare Quote (FR-PRI-013).
 * Client only submits operational parameters (service, vehicle, coordinates, addresses).
 * Pricing rates, surge, and final fare are NEVER accepted from client input.
 */
public record FareQuoteRequest(
        @NotNull(message = "serviceType is required")
        ServiceType serviceType,

        @NotNull(message = "vehicleType is required")
        VehicleType vehicleType,

        @NotNull(message = "pickupLatitude is required")
        @DecimalMin(value = "-90.0", message = "pickupLatitude must be >= -90.0")
        @DecimalMax(value = "90.0", message = "pickupLatitude must be <= 90.0")
        BigDecimal pickupLatitude,

        @NotNull(message = "pickupLongitude is required")
        @DecimalMin(value = "-180.0", message = "pickupLongitude must be >= -180.0")
        @DecimalMax(value = "180.0", message = "pickupLongitude must be <= 180.0")
        BigDecimal pickupLongitude,

        @Size(max = 255, message = "pickupAddress must not exceed 255 characters")
        String pickupAddress,

        @NotNull(message = "dropoffLatitude is required")
        @DecimalMin(value = "-90.0", message = "dropoffLatitude must be >= -90.0")
        @DecimalMax(value = "90.0", message = "dropoffLatitude must be <= 90.0")
        BigDecimal dropoffLatitude,

        @NotNull(message = "dropoffLongitude is required")
        @DecimalMin(value = "-180.0", message = "dropoffLongitude must be >= -180.0")
        @DecimalMax(value = "180.0", message = "dropoffLongitude must be <= 180.0")
        BigDecimal dropoffLongitude,

        @Size(max = 255, message = "dropoffAddress must not exceed 255 characters")
        String dropoffAddress,

        @Size(max = 50, message = "regionId must not exceed 50 characters")
        String regionId
) {
}
