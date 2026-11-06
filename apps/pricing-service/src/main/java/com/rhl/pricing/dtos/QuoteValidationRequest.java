package com.rhl.pricing.dtos;

import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Internal machine-to-machine request for quote validation (FR-PRI-015, M05 Group 2).
 */
public record QuoteValidationRequest(
        @NotNull(message = "quoteId is required")
        UUID quoteId,

        @NotNull(message = "customerId is required")
        UUID customerId,

        @NotNull(message = "serviceType is required")
        ServiceType serviceType,

        @NotNull(message = "vehicleType is required")
        VehicleType vehicleType,

        boolean customerConfirmedSurge
) {
}
