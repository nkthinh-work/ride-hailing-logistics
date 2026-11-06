package com.rhl.pricing.dtos;

import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

public record CreatePricingRuleRequest(
        @NotNull(message = "serviceType is required")
        ServiceType serviceType,

        @NotNull(message = "vehicleType is required")
        VehicleType vehicleType,

        String regionId,

        @NotNull(message = "baseFare is required")
        @DecimalMin(value = "0.0", message = "baseFare must not be negative")
        BigDecimal baseFare,

        @DecimalMin(value = "0.0", message = "includedDistanceKm must not be negative")
        BigDecimal includedDistanceKm,

        @NotNull(message = "pricePerKm is required")
        @DecimalMin(value = "0.0", message = "pricePerKm must not be negative")
        BigDecimal pricePerKm,

        @NotNull(message = "pricePerMinute is required")
        @DecimalMin(value = "0.0", message = "pricePerMinute must not be negative")
        BigDecimal pricePerMinute,

        @NotNull(message = "minimumFare is required")
        @DecimalMin(value = "0.0", message = "minimumFare must not be negative")
        BigDecimal minimumFare,

        String currency,

        @NotNull(message = "effectiveFrom is required")
        Instant effectiveFrom,

        Instant effectiveTo,

        String reason
) {
}
