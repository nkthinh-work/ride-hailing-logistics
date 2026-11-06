package com.rhl.identity.dtos;

import com.rhl.identity.entities.enums.VehicleStatus;
import com.rhl.identity.entities.enums.VehicleType;

import java.time.Instant;
import java.util.UUID;

public record VehicleResponse(
        UUID id,
        UUID driverProfileId,
        VehicleType type,
        String plate,
        String make,
        String model,
        String color,
        VehicleStatus status,
        Instant createdAt
) {
}
