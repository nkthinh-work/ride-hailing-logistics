package com.rhl.dispatch.dtos;

import com.rhl.dispatch.models.ServiceType;
import jakarta.validation.constraints.NotNull;

import java.time.Duration;
import java.util.UUID;

public record CreateOfferCommand(
        @NotNull(message = "tripId is required")
        UUID tripId,

        @NotNull(message = "driverId is required")
        UUID driverId,

        UUID vehicleId,

        @NotNull(message = "serviceType is required")
        ServiceType serviceType,

        Duration ttl
) {
}
