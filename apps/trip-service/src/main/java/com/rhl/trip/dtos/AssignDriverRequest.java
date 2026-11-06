package com.rhl.trip.dtos;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Internal M2M request to assign a driver and vehicle to a trip (FR-MAT-010, FR-MAT-014).
 */
public record AssignDriverRequest(
        @NotNull(message = "driverId is required")
        UUID driverId,

        UUID vehicleId,

        UUID offerId,

        String actorRole
) {
    public AssignDriverRequest(UUID driverId, UUID vehicleId) {
        this(driverId, vehicleId, null, "DRIVER");
    }
}
